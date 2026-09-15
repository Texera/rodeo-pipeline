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

package org.apache.texera.service.resource

import io.dropwizard.auth.Auth
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs._
import jakarta.ws.rs.core.MediaType
import org.apache.texera.auth.SessionUser
import org.apache.texera.common.config.RuntimeImageConfig
import org.apache.texera.dao.SqlServer
import org.apache.texera.dao.jooq.generated.enums.PrivilegeEnum
import org.apache.texera.dao.jooq.generated.tables.daos.UserDao
import org.apache.texera.service.resource.RuntimeImageAccessResource._
import org.jooq.impl.{DSL, SQLDataType}
import org.jooq.{DSLContext, EnumType}

import scala.jdk.CollectionConverters._

object RuntimeImageAccessResource {

  private def context: DSLContext = SqlServer.getInstance().createDSLContext()

  // Plain DSL for runtime_image_user_access, for the same reason RuntimeImageResource uses
  // it for runtime_image: the jOOQ sources are generated against a live database at build
  // time and are not in the repository, so a table this new is not reachable through
  // them from a clean checkout. The user table is old enough that its DAO always exists.
  private val ACCESS = DSL.table(DSL.name("runtime_image_user_access"))
  private val RIID = DSL.field(DSL.name("riid"), classOf[Integer])
  private val UID = DSL.field(DSL.name("uid"), classOf[Integer])
  // asEnumDataType rather than a plain String: privilege is a Postgres enum column, and
  // binding a varchar parameter to it fails outright. The generated DAOs get this for
  // free; plain DSL has to ask for it.
  private val PRIVILEGE =
    DSL.field(DSL.name("privilege"), SQLDataType.VARCHAR.asEnumDataType(classOf[PrivilegeEnum]))

  case class AccessEntry(email: String, name: String, privilege: EnumType)
}

/**
  * Who may use a runtime image besides its owner.
  *
  * Mounted under /access/runtime-image so it joins the same family the frontend's
  * ShareAccessService already addresses generically -- /access/{type}/grant|revoke|list
  * -- which is why sharing a runtime image needed no new client-side plumbing.
  */
@Path("/access/runtime-image")
@Produces(Array(MediaType.APPLICATION_JSON))
@RolesAllowed(Array("REGULAR", "ADMIN"))
class RuntimeImageAccessResource {

  final private val userDao = new UserDao(context.configuration())

  private def requireEnabled(): Unit =
    if (!RuntimeImageConfig.enabled) {
      throw new ServiceUnavailableException("Runtime images are not enabled on this deployment.")
    }

  /**
    * Resolves an email to its user id, throwing BadRequestException (400) when no account
    * matches -- the service registers no ExceptionMapper for IllegalArgumentException, so
    * that would otherwise surface as an opaque HTTP 500. Mirrors ComputingUnitAccessResource.
    */
  private def resolveUidByEmail(email: String): Integer = {
    val user = userDao.fetchOneByEmail(email)
    if (user == null) {
      throw new BadRequestException("User with the given email does not exist")
    }
    user.getUid
  }

  private def requireWriteAccess(riid: Int, uid: Int): Unit = {
    if (!RuntimeImageResource.hasReadAccess(riid, uid)) {
      // Absent rather than forbidden, matching RuntimeImageResource: whether an id exists
      // is not something a stranger should learn from a status code.
      throw new NotFoundException(s"No runtime image $riid available to you.")
    }
    if (!RuntimeImageResource.hasWriteAccess(riid, uid)) {
      throw new ForbiddenException("User does not have permission to change sharing.")
    }
  }

  @GET
  @Path("/list/{riid}")
  def getAccessList(
      @Auth user: SessionUser,
      @PathParam("riid") riid: Integer
  ): List[AccessEntry] = {
    requireEnabled()
    if (!RuntimeImageResource.hasReadAccess(riid, user.getUid)) {
      throw new NotFoundException(s"No runtime image $riid available to you.")
    }
    context
      .select(UID, PRIVILEGE)
      .from(ACCESS)
      .where(RIID.eq(riid))
      .fetch()
      .asScala
      .flatMap { record =>
        // A grantee whose account was removed leaves the row behind only until the
        // cascade runs; skipping it is better than a null-pointer mid-list.
        Option(userDao.fetchOneByUid(record.get(UID))).map { grantee =>
          AccessEntry(
            email = grantee.getEmail,
            name = grantee.getName,
            privilege = record.get(PRIVILEGE)
          )
        }
      }
      .toList
  }

  @PUT
  @Path("/grant/{riid}/{email}/{privilege}")
  def grantAccess(
      @Auth user: SessionUser,
      @PathParam("riid") riid: Integer,
      @PathParam("email") email: String,
      @PathParam("privilege") privilege: PrivilegeEnum
  ): Unit = {
    requireEnabled()
    requireWriteAccess(riid, user.getUid)

    val granteeUid = resolveUidByEmail(email)
    if (RuntimeImageResource.isOwner(riid, granteeUid)) {
      throw new BadRequestException("The owner of a runtime image already has full access to it.")
    }

    // Upsert rather than insert: re-granting an existing grantee updates their privilege
    // in place instead of failing on the (riid, uid) primary key. Mirrors
    // ComputingUnitAccessResource, which uses DAO.merge for the same reason.
    context
      .insertInto(ACCESS)
      .set(RIID, riid)
      .set(UID, granteeUid)
      .set(PRIVILEGE, privilege)
      .onConflict(RIID, UID)
      .doUpdate()
      .set(PRIVILEGE, privilege)
      .execute()
  }

  @DELETE
  @Path("/revoke/{riid}/{email}")
  def revokeAccess(
      @Auth user: SessionUser,
      @PathParam("riid") riid: Integer,
      @PathParam("email") email: String
  ): Unit = {
    requireEnabled()
    val uid = user.getUid
    val granteeUid = resolveUidByEmail(email)

    // Revoking your own grant is how you remove a shared runtime image from your list, so
    // it needs only the access you are giving up -- not the write access that changing
    // someone else's would.
    if (granteeUid != uid) {
      requireWriteAccess(riid, uid)
    } else if (!RuntimeImageResource.hasReadAccess(riid, uid)) {
      throw new NotFoundException(s"No runtime image $riid available to you.")
    }

    context
      .deleteFrom(ACCESS)
      .where(RIID.eq(riid).and(UID.eq(granteeUid)))
      .execute()
  }

  @GET
  @Path("/owner/{riid}")
  @Produces(Array(MediaType.TEXT_PLAIN))
  def getOwner(@Auth user: SessionUser, @PathParam("riid") riid: Integer): String = {
    requireEnabled()
    if (!RuntimeImageResource.hasReadAccess(riid, user.getUid)) {
      throw new NotFoundException(s"No runtime image $riid available to you.")
    }
    RuntimeImageResource
      .ownerUidOf(riid)
      .flatMap(ownerUid => Option(userDao.fetchOneByUid(ownerUid)))
      .map(_.getEmail)
      .getOrElse(throw new NotFoundException(s"Runtime image $riid has no owner on record."))
  }
}
