/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

import java.time.{Instant, LocalDateTime}
import java.util.Locale

import scala.collection.mutable
import scala.reflect.ClassTag

import org.apache.spark.sql.delta.commands.MergeIntoCommand
import org.apache.spark.sql.delta.sources.DeltaSQLConf

import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.catalyst.analysis.EliminateSubqueryAliases
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression
import org.apache.spark.sql.catalyst.optimizer.ComputeCurrentTime
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreePattern.CURRENT_LIKE
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.catalyst.util.DateTimeUtils.{instantToMicros, localDateTimeToMicros}
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DataType, DateType, StringType, StructField, StructType, TimestampNTZType, TimestampType}

case class PreprocessTableMerge(override val conf: SQLConf)
  extends Rule[LogicalPlan] with UpdateExpressionsSupport {

  private var trackHighWaterMarks = Set[String]()

  def getTrackHighWaterMarks: Set[String] = trackHighWaterMarks

  override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperators {
    case m: DeltaMergeInto if m.resolved => apply(m, true)
  }

  def apply(mergeInto: DeltaMergeInto, transformToCommand: Boolean): LogicalPlan = {
    val DeltaMergeInto(
      target,
      source,
      condition,
      matched,
      notMatched,
      notMatchedBySource,
      migrateSchema,
      finalSchemaOpt) = mergeInto

    if (finalSchemaOpt.isEmpty) {
      throw DeltaErrors.targetTableFinalSchemaEmptyException()
    }

    val finalSchema = finalSchemaOpt.get

    def checkCondition(cond: Expression, conditionName: String): Unit = {
      if (!cond.deterministic) {
        throw DeltaErrors.nonDeterministicNotSupportedException(
          s"$conditionName condition of MERGE operation", cond)
      }
      if (cond.find(_.isInstanceOf[AggregateExpression]).isDefined) {
        throw DeltaErrors.aggsNotSupportedException(
          s"$conditionName condition of MERGE operation", cond)
      }
      if (SubqueryExpression.hasSubquery(cond)) {
        throw DeltaErrors.subqueryNotSupportedException(
          s"$conditionName condition of MERGE operation", cond)
      }
    }

    checkCondition(condition, "search")
    (matched ++ notMatched ++ notMatchedBySource).filter(_.condition.nonEmpty).foreach { clause =>
      checkCondition(clause.condition.get, clause.clauseType.toUpperCase(Locale.ROOT))
    }

    val deltaLogicalPlan = EliminateSubqueryAliases(target)
    val tahoeFileIndex = deltaLogicalPlan match {
      case DeltaFullTable(index) => index
      case o => throw DeltaErrors.notADeltaSourceException("MERGE", Some(o))
    }
    val generatedColumns = GeneratedColumn.getGeneratedColumns(
      tahoeFileIndex.snapshotAtAnalysis)
    if (generatedColumns.nonEmpty && !deltaLogicalPlan.isInstanceOf[LogicalRelation]) {
      throw DeltaErrors.operationOnTempViewWithGenerateColsNotSupported("MERGE INTO")
    }
    // Additional columns with default expressions.
    var additionalColumns = Seq[StructField]()

    val processedMatched = matched.map {
      case m: DeltaMergeIntoMatchedUpdateClause =>
        val alignedActions = alignUpdateActions(
          target,
          m.resolvedActions,
          whenClauses = matched ++ notMatched ++ notMatchedBySource,
          identityColumns = additionalColumns,
          generatedColumns = generatedColumns,
          allowStructEvolution = migrateSchema,
          finalSchema = finalSchema)
        m.copy(m.condition, alignedActions)
      case m: DeltaMergeIntoMatchedDeleteClause => m // Delete does not need reordering
    }
    val processedNotMatchedBySource = notMatchedBySource.map {
      case m: DeltaMergeIntoNotMatchedBySourceUpdateClause =>
        val alignedActions = alignUpdateActions(
          target,
          m.resolvedActions,
          whenClauses = matched ++ notMatched ++ notMatchedBySource,
          identityColumns = additionalColumns,
          generatedColumns,
          migrateSchema,
          finalSchema)
        m.copy(m.condition, alignedActions)
      case m: DeltaMergeIntoNotMatchedBySourceDeleteClause => m // Delete does not need reordering
    }

    val processedNotMatched = notMatched.map { case m: DeltaMergeIntoNotMatchedInsertClause =>
      // Check if columns are distinct. All actions should have targetColNameParts.size = 1.
      m.resolvedActions.foreach { a =>
        if (a.targetColNameParts.size > 1) {
          throw DeltaErrors.nestedFieldNotSupported(
            "INSERT clause of MERGE operation",
            a.targetColNameParts.mkString("`", "`.`", "`")
          )
        }
      }


      val targetColNames = m.resolvedActions.map(_.targetColNameParts.head)
      if (targetColNames.distinct.size < targetColNames.size) {
        throw DeltaErrors.duplicateColumnOnInsert()
      }

      // Generate actions for columns that are not explicitly inserted. They might come from
      // the original schema of target table or the schema evolved columns. In either case they are
      // covered by `finalSchema`.
      val implicitActions = finalSchema.filterNot { col =>
        m.resolvedActions.exists { insertAct =>
          conf.resolver(insertAct.targetColNameParts.head, col.name)
        }
      }.map { col =>
        DeltaMergeAction(Seq(col.name), Literal(null, col.dataType), targetColNameResolved = true)
      }

      val actions = m.resolvedActions ++ implicitActions
      val (actionsWithGeneratedColumns, trackFromInsert) = resolveImplicitColumns(
        m.resolvedActions,
        actions,
        source,
        generatedColumns.map(f => (f, true)) ++ additionalColumns.map(f => (f, false)),
        finalSchema)

      trackHighWaterMarks ++= trackFromInsert

      val alignedActions: Seq[DeltaMergeAction] = finalSchema.map { targetAttrib =>
        actionsWithGeneratedColumns.find { a =>
          conf.resolver(targetAttrib.name, a.targetColNameParts.head)
        }.map { a =>
          DeltaMergeAction(
            Seq(targetAttrib.name),
            castIfNeeded(
              a.expr,
              targetAttrib.dataType,
              allowStructEvolution = migrateSchema),
            targetColNameResolved = true)
        }.getOrElse {
          // If a target table column was not found in the INSERT columns and expressions,
          // then throw exception as there must be an expression to set every target column.
          throw DeltaErrors.columnOfTargetTableNotFoundInMergeException(
            targetAttrib.name, targetColNames.mkString(", "))
        }
      }

      m.copy(m.condition, alignedActions)
    }

    if (transformToCommand) {
      val tahoeFileIndex = EliminateSubqueryAliases(target) match {
        case DeltaFullTable(index) => index
        case o => throw DeltaErrors.notADeltaSourceException("MERGE", Some(o))
      }

      /**
       * Because source and target are not children of MergeIntoCommand they are not processed when
       * invoking the [[ComputeCurrentTime]] rule. This is why they need special handling.
       */
      val now = Instant.now()
      // Transform timestamps for the MergeIntoCommand, source, and target using the same instant.
      // Called explicitly because source and target are not children of MergeIntoCommand.
      transformTimestamps(
        MergeIntoCommand(
          transformTimestamps(source, now),
          transformTimestamps(target, now),
          tahoeFileIndex,
          condition,
          processedMatched,
          processedNotMatched,
          processedNotMatchedBySource,
          finalSchemaOpt),
        now)
    } else {
      DeltaMergeInto(
        source,
        target,
        condition,
        processedMatched,
        processedNotMatched,
        processedNotMatchedBySource,
        migrateSchema,
        finalSchemaOpt)
    }
  }

  private def transformTimestamps(plan: LogicalPlan, instant: Instant): LogicalPlan = {
    import org.apache.spark.sql.delta.implicits._

    val currentTimestampMicros = instantToMicros(instant)
    val currentTime = Literal.create(currentTimestampMicros, TimestampType)
    val timezone = Literal.create(conf.sessionLocalTimeZone, StringType)

    plan.transformUpWithSubqueries {
      case subQuery =>
        subQuery.transformAllExpressionsUpWithPruning(_.containsPattern(CURRENT_LIKE)) {
          case cd: CurrentDate =>
            Literal.create(DateTimeUtils.microsToDays(currentTimestampMicros, cd.zoneId), DateType)
          case CurrentTimestamp() | Now() => currentTime
          case CurrentTimeZone() => timezone
          case localTimestamp: LocalTimestamp =>
            val asDateTime = LocalDateTime.ofInstant(instant, localTimestamp.zoneId)
            Literal.create(localDateTimeToMicros(asDateTime), TimestampNTZType)
        }
    }
  }

  /**
   * Generates update expressions for columns that are not present in the target table and are
   * introduced by one of the update or insert merge clauses. The generated update expressions and
   * the update expressions for the existing columns are aligned to match the order in the
   * target output schema.
   *
   * @param target Logical plan node of the target table of merge.
   * @param resolvedActions Merge actions of the update clause being processed.
   * @param whenClauses All merge clauses of the merge operation.
   * @param identityColumns Additional identity columns present in the table.
   * @param generatedColumns List of the generated columns in the table. See
   *                         [[UpdateExpressionsSupport]].
   * @param allowStructEvolution Whether to allow structs to evolve. See
   *                             [[UpdateExpressionsSupport]].
   * @param finalSchema The schema of the target table after the merge operation.
   * @return Update actions aligned on the target output schema `finalSchema`.
   */
  private def alignUpdateActions(
      target: LogicalPlan,
      resolvedActions: Seq[DeltaMergeAction],
      whenClauses: Seq[DeltaMergeIntoClause],
      identityColumns: Seq[StructField],
      generatedColumns: Seq[StructField],
      allowStructEvolution: Boolean,
      finalSchema: StructType)
    : Seq[DeltaMergeAction] = {
    // Get the operations for columns that already exist...
    val existingUpdateOps = resolvedActions.map { a =>
      UpdateOperation(a.targetColNameParts, a.expr)
    }

    // And construct operations for columns that the insert/update clauses will add.
    val newUpdateOps = generateUpdateOpsForNewTargetFields(target, finalSchema, resolvedActions)

    // Get expressions for the final schema for alignment. Note that attributes which already
    // exist in the target need to use the same expression ID, even if the schema will evolve.
    val finalSchemaExprs =
    finalSchema.map { field =>
      target.resolve(Seq(field.name), conf.resolver).map { r =>
        AttributeReference(field.name, field.dataType)(r.exprId)
      }.getOrElse {
        AttributeReference(field.name, field.dataType)()
      }
    }

    // Use the helper methods for in UpdateExpressionsSupport to generate expressions such
    // that nested fields can be updated (only for existing columns).
    val alignedExprs = generateUpdateExpressions(
      finalSchemaExprs,
      existingUpdateOps ++ newUpdateOps,
      conf.resolver,
      allowStructEvolution = allowStructEvolution,
      generatedColumns = generatedColumns)

    val alignedExprsWithGenerationExprs =
      if (alignedExprs.forall(_.nonEmpty)) {
        alignedExprs.map(_.get)
      } else {
        generateUpdateExprsForGeneratedColumns(target, generatedColumns, alignedExprs,
          Some(finalSchemaExprs))
      }

    alignedExprsWithGenerationExprs
      .zip(finalSchemaExprs)
      .map { case (expr, attrib) =>
        DeltaMergeAction(Seq(attrib.name), expr, targetColNameResolved = true)
      }
  }

  /**
   * Generate expressions to set to null the new (potentially nested) fields that are added to the
   * target table by schema evolution and are not already set by any of the `resolvedActions` from
   * the merge clause.
   *
   * @param target Logical plan node of the target table of merge.
   * @param finalSchema The schema of the target table after the merge operation.
   * @param resolvedActions Merge actions of the update clause being processed.
   * @return List of update operations
   */
  private def generateUpdateOpsForNewTargetFields(
      target: LogicalPlan,
      finalSchema: StructType,
      resolvedActions: Seq[DeltaMergeAction])
    : Seq[UpdateOperation] = {
    // Collect all fields in the final schema that were added by schema evolution.
    // `SchemaPruning.pruneSchema` only prunes nested fields, we then filter out top-level fields
    // ourself.
    val targetSchemaBeforeEvolution =
      target.schema.map(SchemaPruning.RootField(_, derivedFromAtt = false))
    val newTargetFields =
      StructType(SchemaPruning.pruneSchema(finalSchema, targetSchemaBeforeEvolution)
      .filterNot { topLevelField => target.schema.exists(_.name == topLevelField.name) })

    /**
     * Remove the field corresponding to `pathFilter` (if any) from `schema`.
     */
    def filterSchema(schema: StructType, pathFilter: Seq[String])
      : Seq[StructField] = schema.flatMap {
        case StructField(name, struct: StructType, _, _)
            if name == pathFilter.head && pathFilter.length > 1 =>
          Some(StructField(name, StructType(filterSchema(struct, pathFilter.drop(1)))))
        case f: StructField if f.name == pathFilter.head => None
        case f => Some(f)
    }
    // Then filter out fields that are set by one of the merge actions.
    val newTargetFieldsWithoutAssignment = resolvedActions
      .map(_.targetColNameParts)
      .foldRight(newTargetFields) {
        (pathFilter, schema) => StructType(filterSchema(schema, pathFilter))
      }

    /**
     * Generate the list of all leaf fields and their corresponding data type from `schema`.
     */
    def leafFields(schema: StructType, prefix: Seq[String] = Seq.empty)
      : Seq[(Seq[String], DataType)] = schema.flatMap { field =>
        val name = prefix :+ field.name.toLowerCase(Locale.ROOT)
        field.dataType match {
          case struct: StructType => leafFields(struct, name)
          case dataType => Seq((name, dataType))
      }
    }
    // Finally, generate an update operation for each remaining field to set it to null.
    leafFields(newTargetFieldsWithoutAssignment).map {
      case (name, dataType) => UpdateOperation(name, Literal(null, dataType))
    }
  }

  /**
   * Resolves any non explicitly inserted generated columns in `allActions` to its
   * corresponding generated expression.
   *
   * For each action, if it's a generated column that is not explicitly inserted, we will
   * use its generated expression to calculate its value by resolving to a fake project of all the
   * inserted values. Note that this fake project is created after we set all non explicitly
   * inserted columns to nulls. This guarantees that all columns referenced by the generated
   * column, regardless of whether they are explicitly inserted or not, will have a
   * corresponding expression in the fake project and hence the generated expression can
   * always be resolved.
   *
   * @param explicitActions Actions explicitly specified by users.
   * @param allActions Actions with non explicitly specified columns added with nulls.
   * @param sourcePlan Logical plan node of the source table of merge.
   * @param columnWithDefaultExpr All the generated columns in the target table.
   * @return `allActions` with expression for non explicitly inserted generated columns expression
   *        resolved.
   */
  private def resolveImplicitColumns(
    explicitActions: Seq[DeltaMergeAction],
    allActions: Seq[DeltaMergeAction],
    sourcePlan: LogicalPlan,
    columnWithDefaultExpr: Seq[(StructField, Boolean)],
    finalSchema: StructType): (Seq[DeltaMergeAction], Set[String]) = {
    val implicitColumns = columnWithDefaultExpr.filter {
      case (field, _) =>
        !explicitActions.exists { insertAct =>
          conf.resolver(insertAct.targetColNameParts.head, field.name)
        }
    }
    if (implicitColumns.isEmpty) {
      return (allActions, Set[String]())
    }
    assert(finalSchema.size == allActions.size)

    val track = mutable.Set[String]()

    // Fake projection used to resolve generated column expressions.
    val fakeProjectMap = allActions.map {
      action => {
        val exprForProject = Alias(action.expr, action.targetColNameParts.head)()
        exprForProject.exprId -> exprForProject
      }
    }.toMap
    val fakeProject = Project(fakeProjectMap.values.toArray[Alias], sourcePlan)

    val resolvedActions = allActions.map { action =>
      val colName = action.targetColNameParts.head
      implicitColumns.find {
        case (field, _) => conf.resolver(field.name, colName)
      } match {
        case Some((field, true)) =>
          val expr = GeneratedColumn.getGenerationExpression(field).get
          val resolvedExpr = resolveReferencesForExpressions(SparkSession.active, expr :: Nil,
            fakeProject).head
          // Replace references to fakeProject with original expression.
          val transformedExpr = resolvedExpr.transform {
            case a: AttributeReference if fakeProjectMap.contains(a.exprId) =>
              fakeProjectMap(a.exprId).child
          }
          action.copy(expr = transformedExpr)
        case _ => action
      }
    }
    (resolvedActions, track.toSet)
  }
}
