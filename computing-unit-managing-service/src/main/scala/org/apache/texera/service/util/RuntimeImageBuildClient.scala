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

package org.apache.texera.service.util

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.api.model.batch.v1.{Job, JobBuilder}
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.apache.texera.common.config.RuntimeImageConfig

import scala.jdk.CollectionConverters._

/**
  * Runs one runtime image build: a Kubernetes Job that turns a user's Dockerfile into an
  * image in the registry.
  *
  * The builder is BuildKit rather than Kaniko, which Google archived in June 2025, and
  * rootless BuildKit specifically -- a build is arbitrary user-supplied instructions, and
  * running it in a privileged pod would make the cluster's isolation depend on the
  * Dockerfile being well behaved. Rootless costs an unconfined seccomp/AppArmor profile
  * and nothing else.
  *
  * The Dockerfile reaches the builder as a ConfigMap rather than through a build context
  * upload, because a Dockerfile is all the context there is: a runtime image has no
  * accompanying files to COPY, so anything it needs it fetches itself.
  */
object RuntimeImageBuildClient extends LazyLogging {

  private val client: io.fabric8.kubernetes.client.KubernetesClient =
    new KubernetesClientBuilder().build()

  private def namespace: String = RuntimeImageConfig.buildNamespace

  /**
    * Tells the builder that the registry speaks plain HTTP.
    *
    * Needed on both sides of the build and easy to get half-right: `registry.insecure` in
    * the output spec only covers the push, while pulling the base image is governed by
    * this file. Without it a build fails at FROM with a TLS error.
    */
  private def buildkitdConfig: String =
    s"""[registry."${RuntimeImageConfig.registry}"]
       |  http = true
       |  insecure = true
       |""".stripMargin

  private def configMapName(riid: Int, buildNumber: Int): String =
    s"${RuntimeImageConfig.buildJobName(riid, buildNumber)}-context"

  /**
    * Starts a build and returns immediately; the job runs on the cluster.
    *
    * Any leftovers from a previous attempt at the same build number are removed first, so
    * a retry after a crash is not blocked by objects that already exist.
    */
  def startBuild(riid: Int, buildNumber: Int, dockerfile: String): Unit = {
    val jobName = RuntimeImageConfig.buildJobName(riid, buildNumber)
    val cmName = configMapName(riid, buildNumber)
    val imageTag = RuntimeImageConfig.imageTagFor(riid, buildNumber)

    deleteBuild(riid, buildNumber)

    val configMap = new ConfigMapBuilder()
      .withNewMetadata()
      .withName(cmName)
      .withNamespace(namespace)
      .addToLabels("texera-runtime-image", riid.toString)
      .endMetadata()
      .withData(Map("Dockerfile" -> dockerfile, "buildkitd.toml" -> buildkitdConfig).asJava)
      .build()

    client.configMaps().inNamespace(namespace).resource(configMap).create()

    val job = buildJob(jobName, cmName, imageTag, riid)
    client.batch().v1().jobs().inNamespace(namespace).resource(job).create()

    logger.info(s"Started runtime image build $jobName producing $imageTag")
  }

  private def buildJob(jobName: String, cmName: String, imageTag: String, riid: Int): Job = {
    val resources = new ResourceRequirementsBuilder()
      .addToRequests("cpu", new Quantity(RuntimeImageConfig.buildCpuRequest))
      .addToRequests("memory", new Quantity(RuntimeImageConfig.buildMemoryRequest))
      .addToLimits("cpu", new Quantity(RuntimeImageConfig.buildCpuLimit))
      .addToLimits("memory", new Quantity(RuntimeImageConfig.buildMemoryLimit))
      .build()

    // Rootless BuildKit needs its process sandbox disabled and the two LSM profiles
    // unconfined, because it is already running as an unprivileged user and cannot
    // set up its own nested namespaces otherwise. AppArmor is set through the pod
    // annotation rather than the container field: the field only exists from Kubernetes
    // 1.30, and the annotation is understood by both.
    val securityContext = new SecurityContextBuilder()
      .withRunAsUser(1000L)
      .withRunAsGroup(1000L)
      .withNewSeccompProfile()
      .withType("Unconfined")
      .endSeccompProfile()
      .build()

    new JobBuilder()
      .withNewMetadata()
      .withName(jobName)
      .withNamespace(namespace)
      .addToLabels("texera-runtime-image", riid.toString)
      .endMetadata()
      .withNewSpec()
      // A failed build is a failed Dockerfile; retrying it unchanged only burns the
      // cluster and confuses the log the user is reading.
      .withBackoffLimit(0)
      .withActiveDeadlineSeconds(RuntimeImageConfig.buildTimeoutSeconds.toLong)
      .withNewTemplate()
      .withNewMetadata()
      .addToLabels("texera-runtime-image", riid.toString)
      .addToAnnotations("container.apparmor.security.beta.kubernetes.io/buildkit", "unconfined")
      .endMetadata()
      .withNewSpec()
      .withRestartPolicy("Never")
      .addNewContainer()
      .withName("buildkit")
      .withImage(RuntimeImageConfig.builderImage)
      .withCommand("buildctl-daemonless.sh")
      .withArgs(
        "build",
        "--frontend=dockerfile.v0",
        "--local=context=/workspace",
        "--local=dockerfile=/workspace",
        s"--output=type=image,name=$imageTag,push=true,registry.insecure=true",
        "--progress=plain"
      )
      .withEnv(
        new EnvVarBuilder()
          .withName("BUILDKITD_FLAGS")
          .withValue("--oci-worker-no-process-sandbox")
          .build(),
        new EnvVarBuilder()
          .withName("XDG_RUNTIME_DIR")
          .withValue("/home/user/.local/tmp")
          .build()
      )
      .withResources(resources)
      .withSecurityContext(securityContext)
      .addNewVolumeMount()
      .withName("workspace")
      .withMountPath("/workspace")
      .endVolumeMount()
      .addNewVolumeMount()
      .withName("buildkitd-config")
      .withMountPath("/home/user/.config/buildkit")
      .endVolumeMount()
      .endContainer()
      .addNewVolume()
      .withName("workspace")
      .withNewConfigMap()
      .withName(cmName)
      .addNewItem()
      .withKey("Dockerfile")
      .withPath("Dockerfile")
      .endItem()
      .endConfigMap()
      .endVolume()
      .addNewVolume()
      .withName("buildkitd-config")
      .withNewConfigMap()
      .withName(cmName)
      .addNewItem()
      .withKey("buildkitd.toml")
      .withPath("buildkitd.toml")
      .endItem()
      .endConfigMap()
      .endVolume()
      .endSpec()
      .endTemplate()
      .endSpec()
      .build()
  }

  sealed trait BuildState
  object BuildState {
    case object Running extends BuildState
    case object Succeeded extends BuildState
    case object Failed extends BuildState

    /** The job is gone -- cleaned up, or never created. */
    case object Absent extends BuildState
  }

  def buildState(riid: Int, buildNumber: Int): BuildState = {
    val job = Option(
      client
        .batch()
        .v1()
        .jobs()
        .inNamespace(namespace)
        .withName(RuntimeImageConfig.buildJobName(riid, buildNumber))
        .get()
    )

    job match {
      case None => BuildState.Absent
      case Some(j) =>
        val status = Option(j.getStatus)
        val succeeded = status.flatMap(s => Option(s.getSucceeded)).exists(_ > 0)
        val failed = status.flatMap(s => Option(s.getFailed)).exists(_ > 0)
        if (succeeded) BuildState.Succeeded
        else if (failed) BuildState.Failed
        else BuildState.Running
    }
  }

  /**
    * The build's output so far.
    *
    * Read from the job's pod, so it is live while the build runs and empty once the pod
    * is gone -- which is why the caller persists the final copy rather than reading
    * through to here every time.
    */
  def buildLog(riid: Int, buildNumber: Int): Option[String] = {
    val pods = client
      .pods()
      .inNamespace(namespace)
      .withLabel("job-name", RuntimeImageConfig.buildJobName(riid, buildNumber))
      .list()
      .getItems
      .asScala
      .toList

    pods.headOption.flatMap { pod =>
      try {
        Option(
          client.pods().inNamespace(namespace).withName(pod.getMetadata.getName).getLog(true)
        )
      } catch {
        // A pod that has not started a container yet has no log to read, which is a
        // normal state during a build rather than something to surface as an error.
        case e: Throwable =>
          logger.debug(s"No log yet for build $riid/$buildNumber: ${e.getMessage}")
          None
      }
    }
  }

  /** Removes a build's job and its ConfigMap. Safe to call when neither exists. */
  def deleteBuild(riid: Int, buildNumber: Int): Unit = {
    val jobName = RuntimeImageConfig.buildJobName(riid, buildNumber)
    try {
      client
        .batch()
        .v1()
        .jobs()
        .inNamespace(namespace)
        .withName(jobName)
        .withPropagationPolicy(DeletionPropagation.BACKGROUND)
        .delete()
      client.configMaps().inNamespace(namespace).withName(configMapName(riid, buildNumber)).delete()
    } catch {
      case e: Throwable => logger.warn(s"Could not clean up build $jobName: ${e.getMessage}")
    }
  }

  /** Removes every build artefact belonging to a runtime image, for when it is deleted. */
  def deleteAllBuilds(riid: Int): Unit = {
    try {
      client
        .batch()
        .v1()
        .jobs()
        .inNamespace(namespace)
        .withLabel("texera-runtime-image", riid.toString)
        .withPropagationPolicy(DeletionPropagation.BACKGROUND)
        .delete()
      client
        .configMaps()
        .inNamespace(namespace)
        .withLabel("texera-runtime-image", riid.toString)
        .delete()
    } catch {
      case e: Throwable =>
        logger.warn(s"Could not clean up builds for runtime image $riid: ${e.getMessage}")
    }
  }
}
