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

package org.apache.texera.amber.core.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.scalalogging.LazyLogging
import org.apache.texera.common.config.EnvironmentalVariable

import java.net.{HttpURLConnection, URI, URL}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.io.Source
import scala.util.Using

/**
  * Lazily FUSE-mounts model versions into the computing unit's local file system.
  *
  * The mount is NOT performed in this (user-accessible, unprivileged) pod. Instead the
  * pod asks the per-node `texera-mounter` (a trusted, privileged DaemonSet) to run
  * GeeseFS on its behalf; the resulting read-only mount is exposed back into this pod
  * through Kubernetes mount propagation, under a host directory scoped to this CU id.
  * A model version is addressed by a locator "<repositoryName>:<commitHash>"; since a
  * commit is immutable, a mount is created at most once per (repository, commit) for the
  * lifetime of the pod and reused by every subsequent worker/execution.
  *
  * The mount is authorized with the pod's per-user JWT, not with global LakeFS
  * credentials: the JWT is handed to the mounter, which passes it to GeeseFS as the S3
  * access key so it reaches file-service's JWT-authenticated S3 proxy exactly as a direct
  * mount would. Neither this pod nor the mounter holds any global LakeFS credential.
  */
object ModelMountManager extends LazyLogging {

  // Root, inside this pod, under which the agent's mounts appear via propagation.
  private val inPodMountRoot: Path =
    Paths.get(sys.env.getOrElse(EnvironmentalVariable.ENV_MOUNT_IN_POD_ROOT, "/mnt/texera-mounts"))

  private val mountTimeoutMs = 30000L

  private val jsonMapper = new ObjectMapper()

  private def env(name: String): String = sys.env.getOrElse(name, "").trim

  private def userJwtToken: String = env(EnvironmentalVariable.ENV_USER_JWT_TOKEN)

  /**
    * Base URL of file-service as reachable from the node (scheme://authority), derived
    * from the presigned-URL endpoint the pod already receives. The mounter's GeeseFS mount
    * targets the S3 proxy hosted at its root.
    */
  private def fileServiceBaseUrl: String = {
    val presignEndpoint = sys.env.getOrElse(
      EnvironmentalVariable.ENV_FILE_SERVICE_GET_PRESIGNED_URL_ENDPOINT,
      "http://localhost:9092/api/dataset/presign-download"
    )
    val uri = new URI(presignEndpoint)
    s"${uri.getScheme}://${uri.getAuthority}"
  }

  /** Base URL of the node-local mounter, reachable at the node IP + mounter port. */
  private def mounterBaseUrl: String = {
    val nodeIp = env(EnvironmentalVariable.ENV_NODE_IP)
    if (nodeIp.isEmpty) {
      throw new RuntimeException(
        s"No ${EnvironmentalVariable.ENV_NODE_IP} present in the computing unit; " +
          "cannot reach the node mounter to mount a model."
      )
    }
    val port = sys.env.getOrElse(EnvironmentalVariable.ENV_MOUNTER_PORT, "8100").trim
    s"http://$nodeIp:$port"
  }

  /** Splits a locator "<repositoryName>:<commitHash>" into its two halves. */
  private def parseLocator(locator: String): (String, String) =
    locator.split(":", 2) match {
      case Array(repo, commit) if repo.nonEmpty && commit.nonEmpty => (repo, commit)
      case _ =>
        throw new IllegalArgumentException(
          s"Invalid model mount locator '$locator'; expected <repositoryName>:<commitHash>."
        )
    }

  /**
    * Where the model version identified by `locator` is (or will be) readable in this pod.
    *
    * Pure: it neither mounts nor checks whether anything is mounted. It exists because the
    * path has to be settled slightly before the mount does — the value handed to a UDF's
    * models parameter is this path, and it is bound while the execution is being set up,
    * whereas the mount itself happens in whichever worker ends up running the operator.
    */
  def mountPointOf(locator: String): Path = {
    val (repositoryName, commitHash) = parseLocator(locator)
    inPodMountRoot.resolve(repositoryName).resolve(commitHash)
  }

  /**
    * Ensure every locator in `locators` is mounted. This is the entry point the region
    * scheduler calls once, before it hands any operator its code, so a region's mounts are
    * deduplicated and performed in one place instead of once per worker.
    *
    * Warning: the mount runs in whatever process calls this, and a FUSE mount is only
    * visible inside that process's mount namespace. It works today because the controller
    * (scheduler) and all of a region's workers share a single computing-unit pod. Should
    * workers ever become separate pods/nodes, mounting here would satisfy only the
    * controller's pod, and this step -- and only this step -- would have to be dispatched
    * to each worker's pod so the mount lands in that worker's namespace.
    */
  def ensureAllMounted(locators: Set[String]): Unit = locators.foreach(ensureMounted)

  /**
    * Ensure the model version identified by the locator "<repositoryName>:<commitHash>"
    * is mounted, and return the local (in-pod) mount point. Thread-safe and idempotent.
    */
  def ensureMounted(locator: String): Path =
    synchronized {
      val (repositoryName, commitHash) = parseLocator(locator)

      val mountPoint = mountPointOf(locator)
      if (isMounted(mountPoint)) {
        logger.info(s"Model $locator already mounted at $mountPoint")
        return mountPoint
      }

      val token = userJwtToken
      if (token.isEmpty) {
        throw new RuntimeException(
          s"No ${EnvironmentalVariable.ENV_USER_JWT_TOKEN} present in the computing unit; " +
            "cannot authorize a model mount without a user JWT."
        )
      }

      requestMount(repositoryName, commitHash, token)

      // The mounter daemonizes GeeseFS; wait until the propagated mount appears in this pod.
      val deadline = System.currentTimeMillis() + mountTimeoutMs
      while (!isMounted(mountPoint)) {
        if (System.currentTimeMillis() > deadline) {
          throw new RuntimeException(
            s"Model $locator did not appear as a mount at $mountPoint within ${mountTimeoutMs}ms."
          )
        }
        Thread.sleep(200)
      }
      logger.info(s"Model $locator mounted at $mountPoint")
      mountPoint
    }

  /**
    * Ask the node mounter to mount `repositoryName`@`commitHash` for this CU. The mounter
    * runs GeeseFS against file-service's S3 proxy with the pod's JWT and mounts into this
    * CU's host directory, from where it propagates back into this pod.
    */
  private def requestMount(repositoryName: String, commitHash: String, token: String): Unit = {
    val payload = jsonMapper.createObjectNode()
    payload.put("cuid", env(EnvironmentalVariable.ENV_CU_ID))
    payload.put("repositoryName", repositoryName)
    payload.put("commitHash", commitHash)
    payload.put("jwt", token)
    payload.put("fileServiceBase", fileServiceBaseUrl)
    val body = jsonMapper.writeValueAsBytes(payload)

    val url = s"$mounterBaseUrl/mount"
    logger.info(s"Requesting mount of $repositoryName:$commitHash from node mounter at $url")
    val connection = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    connection.setRequestMethod("POST")
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setDoOutput(true)
    connection.setConnectTimeout(10000)
    connection.setReadTimeout(mountTimeoutMs.toInt)
    try {
      Using(connection.getOutputStream)(_.write(body))
      val code = connection.getResponseCode
      if (code != HttpURLConnection.HTTP_OK) {
        val err = Option(connection.getErrorStream)
          .map(s => new String(s.readAllBytes(), StandardCharsets.UTF_8))
          .getOrElse("")
        throw new RuntimeException(
          s"Node mounter failed to mount $repositoryName:$commitHash: HTTP $code $err"
        )
      }
    } finally {
      connection.disconnect()
    }
  }

  private def isMounted(mountPoint: Path): Boolean = {
    if (!Files.exists(mountPoint)) {
      return false
    }
    val target = mountPoint.toAbsolutePath.toString
    Using(Source.fromFile("/proc/mounts")) { source =>
      source
        .getLines()
        .exists(line => {
          val fields = line.split(" ")
          fields.length > 2 && fields(1) == target && fields(2).startsWith("fuse")
        })
    }.getOrElse(false)
  }
}
