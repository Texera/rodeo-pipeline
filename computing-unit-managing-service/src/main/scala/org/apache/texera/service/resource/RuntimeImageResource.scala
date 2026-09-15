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

package org.apache.texera.service.resource

import com.typesafe.scalalogging.LazyLogging
import io.dropwizard.auth.Auth
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs._
import jakarta.ws.rs.core.MediaType
import org.apache.texera.auth.SessionUser
import org.apache.texera.common.config.RuntimeImageConfig
import org.apache.texera.dao.SqlServer
import org.apache.texera.dao.jooq.generated.enums.PrivilegeEnum
import org.apache.texera.dao.jooq.generated.tables.daos.UserDao
import org.apache.texera.service.util.RuntimeImageBuildClient
import org.apache.texera.service.util.RuntimeImageBuildClient.BuildState
import org.jooq.impl.{DSL, SQLDataType}
import org.jooq.{Condition, DSLContext, Record}

import java.sql.Timestamp
import scala.jdk.CollectionConverters._

object RuntimeImageResource {

  private def context: DSLContext = SqlServer.getInstance().createDSLContext()

  // Plain DSL rather than generated DAOs: the jOOQ sources are generated against a live
  // database at build time and are not in the repository, so a new table is not reachable
  // through them until that regeneration has happened everywhere. Naming the columns here
  // keeps this table buildable from a clean checkout.
  private val RUNTIME_IMAGE = DSL.table(DSL.name("runtime_image"))
  private val RIID = DSL.field(DSL.name("riid"), classOf[Integer])
  private val UID = DSL.field(DSL.name("uid"), classOf[Integer])
  private val NAME = DSL.field(DSL.name("name"), classOf[String])
  private val DOCKERFILE = DSL.field(DSL.name("dockerfile"), classOf[String])
  private val STATUS = DSL.field(DSL.name("status"), classOf[String])
  private val IMAGE_TAG = DSL.field(DSL.name("image_tag"), classOf[String])
  private val BUILD_NUMBER = DSL.field(DSL.name("build_number"), classOf[Integer])
  private val BUILD_LOG = DSL.field(DSL.name("build_log"), classOf[String])
  private val IS_PUBLIC = DSL.field(DSL.name("is_public"), classOf[java.lang.Boolean])
  private val CREATION_TIME = DSL.field(DSL.name("creation_time"), classOf[Timestamp])
  private val UPDATE_TIME = DSL.field(DSL.name("update_time"), classOf[Timestamp])

  // runtime_image_user_access is as new as runtime_image itself, so it is reached the same
  // way and for the same reason -- see the note above.
  private val ACCESS = DSL.table(DSL.name("runtime_image_user_access"))
  private val ACCESS_RIID = DSL.field(DSL.name("riid"), classOf[Integer])
  private val ACCESS_UID = DSL.field(DSL.name("uid"), classOf[Integer])
  // asEnumDataType rather than a plain String: privilege is a Postgres enum column, and
  // binding a varchar parameter to it fails outright ("column is of type privilege_enum
  // but expression is of type character varying"). This is what the generated DAOs do for
  // the equivalent columns; plain DSL has to ask for it explicitly.
  private val ACCESS_PRIVILEGE =
    DSL.field(DSL.name("privilege"), SQLDataType.VARCHAR.asEnumDataType(classOf[PrivilegeEnum]))

  object Status {
    val Pending = "PENDING"
    val Building = "BUILDING"
    val Ready = "READY"
    val Failed = "FAILED"
  }

  /**
    * What the caller may do with an runtimeImage. OWNER is not a privilege_enum value --
    * ownership is the runtime image's uid column, not a row in the access table -- but the
    * UI needs the three cases distinguished, so it is carried alongside them here.
    */
  object Access {
    val Owner = "OWNER"
    val Write = "WRITE"
    val Read = "READ"
  }

  case class RuntimeImage(
      riid: Int,
      name: String,
      dockerfile: String,
      status: String,
      imageTag: String,
      buildNumber: Int,
      creationTime: Long,
      updateTime: Long,
      isPublic: Boolean,
      ownerEmail: String,
      // How this particular caller reaches it, so the UI can offer Edit and Delete only
      // where they would succeed rather than letting the server reject them.
      access: String
  )

  case class RuntimeImageRequest(name: String, dockerfile: String)
  case class BuildLog(riid: Int, status: String, buildNumber: Int, log: String)
  case class DefaultDockerfile(baseImage: String, dockerfile: String)

  private val NamePattern = "^[A-Za-z0-9][A-Za-z0-9._-]*$".r
  private val MaxNameLength = 128
  private val MaxDockerfileBytes = 256 * 1024

  private def userDao = new UserDao(context.configuration())

  private def emailOf(uid: Integer): String =
    Option(userDao.fetchOneByUid(uid)).map(_.getEmail).getOrElse("")

  private def toRuntimeImage(record: Record, viewerUid: Int): RuntimeImage = {
    val ownerUid = record.get(UID)
    RuntimeImage(
      riid = record.get(RIID),
      name = record.get(NAME),
      dockerfile = record.get(DOCKERFILE),
      status = record.get(STATUS),
      imageTag = record.get(IMAGE_TAG),
      buildNumber = record.get(BUILD_NUMBER),
      creationTime = record.get(CREATION_TIME).getTime,
      updateTime = record.get(UPDATE_TIME).getTime,
      isPublic = record.get(IS_PUBLIC),
      ownerEmail = emailOf(ownerUid),
      access =
        if (ownerUid.intValue() == viewerUid) Access.Owner
        else if (privilegeOf(record.get(RIID).intValue(), viewerUid).contains(Access.Write))
          Access.Write
        else Access.Read
    )
  }

  /** The grant this user holds on this runtime image, if any. Ownership is not a grant. */
  def privilegeOf(riid: Int, uid: Int): Option[String] =
    Option(
      context
        .select(ACCESS_PRIVILEGE)
        .from(ACCESS)
        .where(ACCESS_RIID.eq(riid).and(ACCESS_UID.eq(uid)))
        .fetchOne()
    ).flatMap(r => Option(r.get(ACCESS_PRIVILEGE))).map(_.getLiteral)

  def ownerUidOf(riid: Int): Option[Int] =
    Option(context.select(UID).from(RUNTIME_IMAGE).where(RIID.eq(riid)).fetchOne())
      .map(_.get(UID).intValue())

  def isOwner(riid: Int, uid: Int): Boolean = ownerUidOf(riid).contains(uid)

  def isPublic(riid: Int): Boolean =
    Option(context.select(IS_PUBLIC).from(RUNTIME_IMAGE).where(RIID.eq(riid)).fetchOne())
      .exists(_.get(IS_PUBLIC).booleanValue())

  /**
    * Read access is what starting a computing unit from an runtime image requires: the
    * Dockerfile and the built image can be used, but not changed.
    */
  def hasReadAccess(riid: Int, uid: Int): Boolean =
    isOwner(riid, uid) || isPublic(riid) || privilegeOf(riid, uid).exists(p =>
      p == Access.Read || p == Access.Write
    )

  /**
    * Write access covers editing, rebuilding and publishing. Being public does not confer
    * it -- publishing offers an image to use, not a Dockerfile for anyone to rewrite.
    */
  def hasWriteAccess(riid: Int, uid: Int): Boolean =
    isOwner(riid, uid) || privilegeOf(riid, uid).contains(Access.Write)

  /**
    * Every runtime image this user may start a computing unit from: their own, ones shared
    * with them, and public ones. Expressed as a single condition so that listing and
    * reconciling cannot drift apart on what "visible" means.
    */
  private def visibleTo(uid: Int): Condition =
    UID
      .eq(uid)
      .or(IS_PUBLIC.isTrue)
      .or(
        RIID.in(
          DSL.select(ACCESS_RIID).from(ACCESS).where(ACCESS_UID.eq(uid))
        )
      )

  /**
    * The image a computing unit should be started from for this runtime image, or None if
    * the runtime image is not one this user can start from.
    *
    * Read access rather than ownership: an runtime image shared with this user, or made
    * public by its owner, is one they may start from. That is the whole point of sharing
    * one -- the image is the thing being shared.
    *
    * Used by the computing-unit manager, which is why it is here rather than reached
    * through the REST layer.
    */
  def readyImageFor(riid: Int, uid: Int): Option[String] = {
    if (!hasReadAccess(riid, uid)) return None
    val record = Option(
      context
        .select(STATUS, IMAGE_TAG)
        .from(RUNTIME_IMAGE)
        .where(RIID.eq(riid))
        .fetchOne()
    )
    record.flatMap { r =>
      if (r.get(STATUS) == Status.Ready) Option(r.get(IMAGE_TAG)) else None
    }
  }

  def nameOf(riid: Int): Option[String] =
    Option(context.select(NAME).from(RUNTIME_IMAGE).where(RIID.eq(riid)).fetchOne())
      .map(_.get(NAME))
}

/**
  * Runtime images: a Dockerfile a user owns, and the image built from it.
  *
  * The build is asynchronous by nature -- it takes minutes -- so every write here returns
  * as soon as the job is submitted, and the row's status is what the caller polls. That is
  * also what makes the log readable after navigating away: it is kept on the row, not held
  * in a request.
  */
@Path("/runtime-image")
@Produces(Array(MediaType.APPLICATION_JSON))
class RuntimeImageResource extends LazyLogging {

  import RuntimeImageResource._

  private def requireEnabled(): Unit =
    if (!RuntimeImageConfig.enabled) {
      throw new ServiceUnavailableException("Runtime images are not enabled on this deployment.")
    }

  private def validate(request: RuntimeImageRequest): Unit = {
    val name = Option(request.name).map(_.trim).getOrElse("")
    if (!NamePattern.pattern.matcher(name).matches() || name.length > MaxNameLength) {
      throw new BadRequestException(
        "Runtime image name must start with a letter or digit and contain only letters, " +
          "digits, dots, hyphens and underscores."
      )
    }
    val dockerfile = Option(request.dockerfile).getOrElse("")
    if (dockerfile.trim.isEmpty) {
      throw new BadRequestException("Dockerfile cannot be empty.")
    }
    if (dockerfile.getBytes("UTF-8").length > MaxDockerfileBytes) {
      throw new BadRequestException(s"Dockerfile exceeds $MaxDockerfileBytes bytes.")
    }
    // A Dockerfile reaches the builder through a ConfigMap, and a build that produced no
    // image would fail confusingly later; catching the obvious case here is much clearer.
    if (!dockerfile.linesIterator.exists(_.trim.toUpperCase.startsWith("FROM "))) {
      throw new BadRequestException("Dockerfile must contain a FROM instruction.")
    }
  }

  /** What a new runtime image's editor is pre-filled with. */
  @GET
  @RolesAllowed(Array("REGULAR", "ADMIN"))
  @Path("/default-dockerfile")
  def getDefaultDockerfile(@Auth user: SessionUser): DefaultDockerfile = {
    requireEnabled()
    DefaultDockerfile(RuntimeImageConfig.baseImage, RuntimeImageConfig.defaultDockerfile)
  }

  @GET
  @RolesAllowed(Array("REGULAR", "ADMIN"))
  @Path("")
  def list(@Auth user: SessionUser): List[RuntimeImage] = {
    requireEnabled()
    val uid = user.getUid.intValue()
    // Reconciled on read: a build finishes on the cluster, not in this service, so the
    // row only learns about it when someone looks. That keeps the whole feature free of
    // background threads and leader election, at the cost of a status that is stale until
    // the next poll -- which the UI does anyway while a build is running.
    reconcileRunningBuilds(uid)
    context
      .select()
      .from(RUNTIME_IMAGE)
      .where(visibleTo(uid))
      .orderBy(CREATION_TIME.desc())
      .fetch()
      .asScala
      .map(toRuntimeImage(_, uid))
      .toList
  }

  @GET
  @RolesAllowed(Array("REGULAR", "ADMIN"))
  @Path("/{riid}")
  def get(@PathParam("riid") riid: Int, @Auth user: SessionUser): RuntimeImage = {
    requireEnabled()
    reconcileRunningBuilds(user.getUid.intValue())
    fetchReadable(riid, user.getUid.intValue())
  }

  @POST
  @RolesAllowed(Array("REGULAR", "ADMIN"))
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Path("")
  def create(request: RuntimeImageRequest, @Auth user: SessionUser): RuntimeImage = {
    requireEnabled()
    validate(request)
    val uid = user.getUid.intValue()
    val name = request.name.trim

    val exists = context.fetchExists(
      context.selectFrom(RUNTIME_IMAGE).where(UID.eq(uid).and(NAME.eq(name)))
    )
    if (exists) {
      throw new BadRequestException(s"You already have a runtime image named '$name'.")
    }

    val now = new Timestamp(System.currentTimeMillis())
    val riid = context
      .insertInto(RUNTIME_IMAGE)
      .set(UID, Integer.valueOf(uid))
      .set(NAME, name)
      .set(DOCKERFILE, request.dockerfile)
      .set(STATUS, Status.Pending)
      .set(BUILD_NUMBER, Integer.valueOf(0))
      .set(CREATION_TIME, now)
      .set(UPDATE_TIME, now)
      .returning(RIID)
      .fetchOne()
      .get(RIID)

    startBuild(riid, request.dockerfile)
    fetchReadable(riid, uid)
  }

  /** Editing an runtime image rebuilds it: the image is what the Dockerfile says it is. */
  @PUT
  @RolesAllowed(Array("REGULAR", "ADMIN"))
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Path("/{riid}")
  def update(
      @PathParam("riid") riid: Int,
      request: RuntimeImageRequest,
      @Auth user: SessionUser
  ): RuntimeImage = {
    requireEnabled()
    validate(request)
    val uid = user.getUid.intValue()
    val existing = fetchWritable(riid, uid)
    val name = request.name.trim

    if (name != existing.name) {
      // The (uid, name) uniqueness this protects is the owner's, not the editor's: a
      // grantee renaming a shared runtime image must not collide with the owner's others.
      val ownerUid = Integer.valueOf(ownerUidOf(riid).getOrElse(uid))
      val clash = context.fetchExists(
        context
          .selectFrom(RUNTIME_IMAGE)
          .where(UID.eq(ownerUid).and(NAME.eq(name)).and(RIID.ne(riid)))
      )
      if (clash)
        throw new BadRequestException(s"Its owner already has a runtime image named '$name'.")
    }

    context
      .update(RUNTIME_IMAGE)
      .set(NAME, name)
      .set(DOCKERFILE, request.dockerfile)
      .set(UPDATE_TIME, new Timestamp(System.currentTimeMillis()))
      .where(RIID.eq(riid))
      .execute()

    startBuild(riid, request.dockerfile)
    fetchReadable(riid, uid)
  }

  /** Rebuilds without editing -- for a build that failed on something since fixed. */
  @POST
  @RolesAllowed(Array("REGULAR", "ADMIN"))
  @Path("/{riid}/rebuild")
  def rebuild(@PathParam("riid") riid: Int, @Auth user: SessionUser): RuntimeImage = {
    requireEnabled()
    val uid = user.getUid.intValue()
    val existing = fetchWritable(riid, uid)
    startBuild(riid, existing.dockerfile)
    fetchReadable(riid, uid)
  }

  @GET
  @RolesAllowed(Array("REGULAR", "ADMIN"))
  @Path("/{riid}/logs")
  def logs(@PathParam("riid") riid: Int, @Auth user: SessionUser): BuildLog = {
    requireEnabled()
    val uid = user.getUid.intValue()
    val runtimeImage = fetchReadable(riid, uid)

    // While the job exists its pod is the live copy and the stored one is behind; once it
    // is gone the stored copy is all there is. Preferring whichever is longer is how a
    // user gets a complete log in both cases without the endpoint needing to know which
    // phase the build is in.
    val live = RuntimeImageBuildClient.buildLog(riid, runtimeImage.buildNumber).getOrElse("")
    val stored = Option(
      context.select(BUILD_LOG).from(RUNTIME_IMAGE).where(RIID.eq(riid)).fetchOne()
    ).flatMap(r => Option(r.get(BUILD_LOG))).getOrElse("")

    val best = if (live.length >= stored.length) live else stored
    val reconciled = reconcileOne(riid, uid)
    BuildLog(riid, reconciled.status, reconciled.buildNumber, best)
  }

  @DELETE
  @RolesAllowed(Array("REGULAR", "ADMIN"))
  @Path("/{riid}")
  def delete(@PathParam("riid") riid: Int, @Auth user: SessionUser): Unit = {
    requireEnabled()
    val uid = user.getUid.intValue()
    // Owner only, deliberately narrower than editing. Deleting takes the image away from
    // everyone it was shared with, and a WRITE grantee was given the right to change an
    // runtime image, not to remove it from the people who depend on it.
    if (!isOwner(riid, uid)) {
      fetchReadable(riid, uid) // 404 rather than 403 for something they cannot even see
      throw new ForbiddenException("Only a runtime image's owner can delete it.")
    }
    RuntimeImageBuildClient.deleteAllBuilds(riid)
    context.deleteFrom(RUNTIME_IMAGE).where(RIID.eq(riid).and(UID.eq(uid))).execute()
  }

  /**
    * Publishing an runtime image makes its image startable by anyone, exactly as publishing
    * a dataset makes its files readable by anyone. The Dockerfile stays the owner's: a
    * reader can use the image, not rewrite the instructions that produced it.
    */
  @POST
  @RolesAllowed(Array("REGULAR", "ADMIN"))
  @Path("/{riid}/update/publicity")
  def togglePublicity(@PathParam("riid") riid: Int, @Auth user: SessionUser): RuntimeImage = {
    requireEnabled()
    val uid = user.getUid.intValue()
    fetchWritable(riid, uid)
    context
      .update(RUNTIME_IMAGE)
      .set(IS_PUBLIC, DSL.not(IS_PUBLIC))
      .set(UPDATE_TIME, new Timestamp(System.currentTimeMillis()))
      .where(RIID.eq(riid))
      .execute()
    fetchReadable(riid, uid)
  }

  /**
    * An runtime image this user may use: their own, one shared with them, or a public one.
    * Anything else is reported as absent rather than forbidden, so that listing which
    * runtime image ids exist is not something a stranger can do by reading status codes.
    */
  private def fetchReadable(riid: Int, uid: Int): RuntimeImage = {
    if (!hasReadAccess(riid, uid)) {
      throw new NotFoundException(s"No runtime image $riid available to you.")
    }
    val record = Option(context.select().from(RUNTIME_IMAGE).where(RIID.eq(riid)).fetchOne())
    record.map(toRuntimeImage(_, uid)).getOrElse {
      throw new NotFoundException(s"No runtime image $riid available to you.")
    }
  }

  /** An runtime image this user may change: their own, or one shared with WRITE. */
  private def fetchWritable(riid: Int, uid: Int): RuntimeImage = {
    val runtimeImage = fetchReadable(riid, uid)
    if (!hasWriteAccess(riid, uid)) {
      throw new ForbiddenException(s"You have read-only access to runtime image $riid.")
    }
    runtimeImage
  }

  private def startBuild(riid: Int, dockerfile: String): Unit = {
    // A new build supersedes whatever was already running for this runtimeImage. Without
    // this, editing or rebuilding while a build is in flight leaves the old job alive to
    // compete with the new one for the same cluster -- and its result is discarded anyway,
    // because finishing a build only writes back to the row for its own build number.
    RuntimeImageBuildClient.deleteAllBuilds(riid)

    val next = context
      .update(RUNTIME_IMAGE)
      .set(BUILD_NUMBER, BUILD_NUMBER.add(1))
      .set(STATUS, Status.Building)
      .set(BUILD_LOG, "")
      .set(UPDATE_TIME, new Timestamp(System.currentTimeMillis()))
      .where(RIID.eq(riid))
      .returning(BUILD_NUMBER)
      .fetchOne()
      .get(BUILD_NUMBER)

    try {
      RuntimeImageBuildClient.startBuild(riid, next, dockerfile)
    } catch {
      case e: Throwable =>
        // The job never started, so nothing on the cluster will ever move this row off
        // BUILDING. Record why here or the runtime image sits building forever.
        logger.error(s"Could not start build for runtime image $riid", e)
        context
          .update(RUNTIME_IMAGE)
          .set(STATUS, Status.Failed)
          .set(BUILD_LOG, s"Could not start the build job: ${e.getMessage}")
          .where(RIID.eq(riid))
          .execute()
    }
  }

  private def reconcileRunningBuilds(uid: Int): Unit = {
    // Scoped to what this user can see rather than what they own, so a shared or public
    // runtime image's status is up to date for the person about to start a unit from it.
    val building = context
      .select(RIID, BUILD_NUMBER, UPDATE_TIME)
      .from(RUNTIME_IMAGE)
      .where(visibleTo(uid).and(STATUS.eq(Status.Building)))
      .fetch()
      .asScala
      .map(r => (r.get(RIID).intValue(), r.get(BUILD_NUMBER).intValue(), r.get(UPDATE_TIME)))
      .toList

    building.foreach {
      case (riid, buildNumber, startedAt) => reconcile(riid, buildNumber, startedAt.getTime)
    }
  }

  private def reconcileOne(riid: Int, uid: Int): RuntimeImage = {
    val runtimeImage = fetchReadable(riid, uid)
    if (runtimeImage.status == Status.Building) {
      reconcile(riid, runtimeImage.buildNumber, runtimeImage.updateTime)
      fetchReadable(riid, uid)
    } else runtimeImage
  }

  // A job is created just after the row is marked BUILDING, and the two are not atomic.
  // Within this window "no such job" means "not submitted yet", not "gone", so the row is
  // left alone rather than being failed by a poll that arrived in between.
  private val JobVisibilityGraceMillis = 120000L

  private def reconcile(riid: Int, buildNumber: Int, startedAtMillis: Long): Unit = {
    // A cluster that cannot be reached right now is a reason to leave the row as it is,
    // not a reason to fail the request that happened to trigger the check. The next read
    // reconciles instead.
    val state =
      try RuntimeImageBuildClient.buildState(riid, buildNumber)
      catch {
        case e: Throwable =>
          logger.warn(s"Could not check build state for runtime image $riid: ${e.getMessage}")
          BuildState.Running
      }

    state match {
      case BuildState.Running => ()

      case BuildState.Succeeded =>
        finish(
          riid,
          buildNumber,
          Status.Ready,
          Some(RuntimeImageConfig.imageTagFor(riid, buildNumber))
        )

      case BuildState.Failed =>
        finish(riid, buildNumber, Status.Failed, None)

      case BuildState.Absent =>
        if (System.currentTimeMillis() - startedAtMillis < JobVisibilityGraceMillis) {
          // Too soon to conclude anything; the job may not have been created yet.
          ()
        } else {
          // The job was cleaned up before anyone read its result. The image either exists
          // in the registry or it does not, and this service cannot tell after the fact,
          // so the honest outcome is a failure the user can retry rather than a READY
          // that might not pull.
          finish(riid, buildNumber, Status.Failed, None)
        }
    }
  }

  private def finish(
      riid: Int,
      buildNumber: Int,
      status: String,
      imageTag: Option[String]
  ): Unit = {
    // Captured before the job is deleted, because deleting it takes the pod's log with it.
    val log = RuntimeImageBuildClient
      .buildLog(riid, buildNumber)
      .getOrElse("The build produced no output, or its log was already cleaned up.")

    val update = context
      .update(RUNTIME_IMAGE)
      .set(STATUS, status)
      .set(BUILD_LOG, log)
      .set(UPDATE_TIME, new Timestamp(System.currentTimeMillis()))

    imageTag
      .map(tag => update.set(IMAGE_TAG, tag))
      .getOrElse(update)
      .where(RIID.eq(riid).and(BUILD_NUMBER.eq(Integer.valueOf(buildNumber))))
      .execute()

    RuntimeImageBuildClient.deleteBuild(riid, buildNumber)
  }
}
