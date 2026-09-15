/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.amber.operator.udf.python

import org.apache.texera.amber.core.executor.{OpExecInitInfo, OpExecWithCode}
import org.apache.texera.amber.core.storage.{FileResolver, ModelMountManager}
import org.apache.texera.amber.core.workflow.ExecutionTimeBinding

/**
  * Shared handling of a Python UDF's UI parameters, between the operator descriptors and
  * [[PythonUdfUiParameterInjector]].
  *
  * Most parameters carry their value straight through to the generated code. One that names
  * a resource does not: the user picks a model or dataset *version*, and what the UDF wants
  * is the directory that version is readable at inside the computing unit. Getting from one
  * to the other means asking the database which LakeFS repository and commit the version is,
  * which is why it does not happen while compiling — see the execution-time binding below.
  */
object PythonUdfUiParameterSupport {

  /** Parameter kinds whose value names a Texera resource rather than being free text. */
  val ModelInputType = "model"
  val DatasetInputType = "dataset"

  /**
    * How each resource kind's version path resolves to a LakeFS repository and commit. Keep
    * in sync with pytexera's `Resource` and the frontend parser's resource input types.
    */
  private val ResourceResolvers: Map[String, String => (String, String)] = Map(
    ModelInputType -> FileResolver.resolveModelVersion,
    DatasetInputType -> FileResolver.resolveDatasetVersion
  )

  private def resourceResolver(parameter: UiUDFParameter): Option[String => (String, String)] =
    Option(parameter.inputType).map(_.trim.toLowerCase).flatMap(ResourceResolvers.get)

  def isResourceParameter(parameter: UiUDFParameter): Boolean =
    resourceResolver(parameter).isDefined

  private def parameterName(parameter: UiUDFParameter): String =
    Option(parameter.attribute).map(_.getName).getOrElse("")

  private def declaredParameters(uiParameters: List[UiUDFParameter]): List[UiUDFParameter] =
    Option(uiParameters).getOrElse(List.empty).filter(_ != null)

  private def selectedResourcePath(parameter: UiUDFParameter): String = {
    val resourcePath = Option(parameter.value).map(_.trim).getOrElse("")
    if (resourcePath.isEmpty) {
      throw new RuntimeException(
        s"No ${parameter.inputType.trim.toLowerCase} selected for the parameter " +
          s"'${parameterName(parameter)}'."
      )
    }
    resourcePath
  }

  /**
    * Code to compile against.
    *
    * A resource parameter keeps the version path the user picked, so the generated code is
    * complete and type-correct without resolving anything. That value is not the one the UDF
    * will see: the execution-time binding replaces it with a mount path before the operator
    * runs.
    */
  def injectForCompilation(code: String, uiParameters: List[UiUDFParameter]): String = {
    val declared = declaredParameters(uiParameters)
    // Cheap enough to check on every compile, and much better caught in the editor than
    // at the start of a run.
    declared.filter(isResourceParameter).foreach(selectedResourcePath)
    PythonUdfUiParameterInjector.inject(code, declared)
  }

  /**
    * The deferred half: resolves each resource parameter to the directory its version is
    * mounted at, and generates the code the workers actually run.
    *
    * None when the UDF names no resources, in which case the code compilation produced is
    * already the code to run and there is nothing to defer.
    */
  def executionBinding(
      code: String,
      uiParameters: List[UiUDFParameter]
  ): Option[ExecutionTimeBinding] = {
    val declared = declaredParameters(uiParameters)
    Option.when(declared.exists(isResourceParameter))(new PythonUdfExecutionBinding(code, declared))
  }

  /**
    * Resolution of a Python UDF's resource parameters, put off until the execution starts.
    *
    * Two things have to happen that compilation should not pay for. Each resource parameter
    * names a model or dataset version by path, and turning that into the LakeFS repository
    * and commit behind it is a database round trip — one per parameter, on a path that runs
    * again every time the workflow being edited is recompiled. And the value the UDF ends up
    * with is a directory inside a computing unit, which is not something the compiler can
    * know.
    *
    * Both results come out of one memoized resolution: the code, with each resource
    * parameter rewritten to its mount path, and the locators behind those paths. The mount
    * itself is left to the region scheduler, which mounts every locator its operators name
    * before it hands any of them this code.
    */
  private class PythonUdfExecutionBinding(
      code: String,
      uiParameters: List[UiUDFParameter]
  ) extends ExecutionTimeBinding {

    private case class Resolved(
        parameters: List[UiUDFParameter],
        mountedModels: Map[String, String]
    )

    private lazy val resolved: Resolved = {
      val locators = scala.collection.mutable.LinkedHashMap.empty[String, String]

      val bound = uiParameters.map { parameter =>
        resourceResolver(parameter) match {
          case None => parameter
          case Some(resolve) =>
            val (repositoryName, commitHash) = resolve(selectedResourcePath(parameter))
            val locator = s"$repositoryName:$commitHash"
            locators += (parameterName(parameter) -> locator)

            val mounted = new UiUDFParameter()
            mounted.attribute = parameter.attribute
            mounted.inputType = parameter.inputType
            mounted.value = ModelMountManager.mountPointOf(locator).toString
            mounted
        }
      }

      Resolved(bound, locators.toMap)
    }

    override lazy val opExecInitInfo: OpExecInitInfo =
      OpExecWithCode(PythonUdfUiParameterInjector.inject(code, resolved.parameters), "python")

    override def mountedModels: Map[String, String] = resolved.mountedModels
  }
}
