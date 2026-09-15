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

package org.apache.texera.amber.core.workflow

import org.apache.texera.amber.core.executor.OpExecInitInfo

/**
  * Setup a physical operator deliberately leaves until its execution starts.
  *
  * Everything here could in principle be computed while compiling — it is derived from
  * properties the user already set. It is left until later for two reasons. Compilation
  * runs again on every edit to a workflow, so anything expensive there is paid hundreds
  * of times for a workflow that may never be run; and some of the answers belong to the
  * computing unit that will run the operator, not to whoever compiled it.
  *
  * The Python UDF's `models` parameters are both: resolving one costs a database round
  * trip, and what it resolves to is a directory inside a particular computing unit.
  *
  * Implementations must be idempotent and should memoize — the engine may ask more than
  * once per execution, and a region that re-executes asks again.
  */
trait ExecutionTimeBinding {

  /** Executor initialization info with every late-bound value in place. */
  def opExecInitInfo: OpExecInitInfo

  /**
    * Model versions that have to be mounted before this operator can run: variable name ->
    * locator "<repositoryName>:<commitHash>". Mounting them is the region scheduler's job,
    * not the operator's. Empty when the operator names no models.
    */
  def mountedModels: Map[String, String]
}
