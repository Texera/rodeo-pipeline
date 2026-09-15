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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.texera.common.config

import com.typesafe.config.{Config, ConfigFactory}

/**
  * Settings for runtime images -- the image a computing unit runs, built from a
  * Dockerfile its owner wrote.
  *
  * Shares kubernetes.conf with [[KubernetesConfig]] because everything here is about
  * running a build on the same cluster that runs the computing units.
  */
object RuntimeImageConfig {

  private val conf: Config = ConfigFactory.parseResources("kubernetes.conf").resolve()

  val enabled: Boolean = conf.getBoolean("runtime-images.enabled")

  val builderImage: String = conf.getString("runtime-images.builder-image")
  val buildNamespace: String = conf.getString("runtime-images.build-namespace")

  /** Host:port that built images are pushed to and pulled from. */
  val registry: String = conf.getString("runtime-images.registry")

  /** What a new runtime image's editor starts from, and what user Dockerfiles build FROM. */
  val baseImage: String = conf.getString("runtime-images.base-image")

  val buildTimeoutSeconds: Int = conf.getInt("runtime-images.build-timeout-seconds")

  val buildCpuRequest: String = conf.getString("runtime-images.build-cpu-request")
  val buildMemoryRequest: String = conf.getString("runtime-images.build-memory-request")
  val buildCpuLimit: String = conf.getString("runtime-images.build-cpu-limit")
  val buildMemoryLimit: String = conf.getString("runtime-images.build-memory-limit")

  /**
    * The image reference a given build produces.
    *
    * Keyed by runtime image id and build number rather than by name: the number means a
    * rebuild publishes a new reference instead of mutating one that running pods were
    * started from, and the id means renaming a runtime image cannot collide with another.
    */
  def imageTagFor(riid: Int, buildNumber: Int): String =
    s"$registry/texera-runtime-image/$riid:$buildNumber"

  /** Kubernetes object name for one build. Unique per build so retries never collide. */
  def buildJobName(riid: Int, buildNumber: Int): String = s"runtime-image-build-$riid-$buildNumber"

  /**
    * The Dockerfile a new runtime image is created with.
    *
    * It is deliberately a working, complete file rather than a comment telling the user
    * what to do: the point of showing it is that they can see what the computing-unit
    * image already provides before deciding what to add to it.
    */
  def defaultDockerfile: String =
    s"""# The computing-unit image. Everything Texera needs to run a workflow -- the Amber
       |# engine, its Python worker and that worker's dependencies -- is already in here,
       |# so a runtime image only has to add what your own code needs.
       |#
       |# Keep this FROM line: an image that does not build on it cannot run as a
       |# computing unit.
       |FROM $baseImage
       |
       |# The image runs unprivileged. Switch to root to install, and switch back at the
       |# end -- a computing unit that runs as root would be a privilege escalation.
       |USER root
       |
       |# System packages go here. This is the whole reason runtime images exist: a Python
       |# virtual environment could never install one.
       |# RUN apt-get update && apt-get install -y --no-install-recommends \\
       |#       your-package \\
       |#     && rm -rf /var/lib/apt/lists/*
       |
       |# Python packages for the engine's own interpreter.
       |# RUN pip3 install --no-cache-dir your-package==1.2.3
       |
       |USER texera
       |""".stripMargin
}
