/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import scala.collection.mutable

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.{And, Attribute, AttributeSet, Expression, NamedExpression, PredicateHelper}
import org.apache.spark.sql.catalyst.plans.{Inner, InnerLike, JoinType}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.internal.SQLConf

/**
 * Created by Kyle on 2017-11-26.
 */
case class HyperCubeJoin(conf: SQLConf) extends Rule[LogicalPlan] with PredicateHelper{
  // val planIndexMap: mutable.HashMap[LogicalPlan, Int] = new mutable.HashMap()
  // var index: Int = 0

  def apply(plan: LogicalPlan): LogicalPlan = {
    if (!conf.hyperCubeJoinEnabled) {
      plan
    } else {
      plan transformDown {
        case j @ Join(_, _, _: InnerLike, Some(_)) =>
          // planIndexMap.clear()
          // index = 0
          createHyperCubeJoin(j)
        case p @ Project(projectList, Join(_, _, _: InnerLike, Some(_)))
          if projectList.forall(_.isInstanceOf[Attribute]) =>
          // planIndexMap.clear()
          // index = 0
          createHyperCubeJoin(p)
      }
    }
  }

  /**
    * Extracts items of consecutive inner joins and join conditions.
    * This method works for bushy trees and left/right deep trees.
    */
  private def extractInnerJoins(plan: LogicalPlan):
    (Seq[LogicalPlan], Seq[Expression]) = {

    plan match {
      case Join(left, right, _: InnerLike, Some(cond)) =>
        val (leftPlans, leftConditions) = extractInnerJoins(left)
        val (rightPlans, rightConditions) = extractInnerJoins(right)
        (leftPlans ++ rightPlans, leftConditions ++
          splitConjunctivePredicates(cond) ++ rightConditions)
      case Project(projectList, j @ Join(_, _, _: InnerLike, Some(cond)))
        if projectList.forall(_.isInstanceOf[Attribute]) => {
        extractInnerJoins(j)
      }
      case _ =>
        // planIndexMap.put(plan, index)
        // index += 1
        (Seq(plan), Seq())
    }
  }


  private def createHyperCubeJoin(plan: LogicalPlan): LogicalPlan = {

    val (children, conditions):
      (Seq[LogicalPlan], Seq[Expression]) = extractInnerJoins(plan)

    plan match {
      case Join(_, _, _, _) if children.size > 2 =>
        // val mapCopy = planIndexMap
        MultiWayJoin(children, Inner, conditions, plan)
      case Project(projectList, Join(_, _, _, _)) if children.size > 2 =>
        // val mapCopy = planIndexMap
        Project(projectList,
          MultiWayJoin(children, Inner, conditions, plan))
      case _ => plan
    }
  }
}
