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

import jakarta.ws.rs.{BadRequestException, ForbiddenException, NotFoundException}
import org.apache.texera.auth.SessionUser
import org.apache.texera.dao.MockTexeraDB
import org.apache.texera.dao.jooq.generated.enums.{PrivilegeEnum, UserRoleEnum}
import org.apache.texera.dao.jooq.generated.tables.daos.UserDao
import org.apache.texera.dao.jooq.generated.tables.pojos.User
import org.jooq.impl.DSL
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Timestamp

/**
  * Spec for [[RuntimeImageAccessResource]] and the access helpers on
  * [[RuntimeImageResource]]'s companion object, backed by an embedded Postgres.
  *
  * The runtime image tables are reached with plain DSL here for the same reason the
  * production code reaches them that way: they are newer than the checked-in jOOQ
  * sources, so there are no generated DAOs for them.
  */
class RuntimeImageAccessResourceSpec
    extends AnyFlatSpec
    with Matchers
    with MockTexeraDB
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  private val RUNTIME_IMAGE = DSL.table(DSL.name("runtime_image"))
  private val ACCESS = DSL.table(DSL.name("runtime_image_user_access"))
  private val RIID = DSL.field(DSL.name("riid"), classOf[Integer])
  private val UID = DSL.field(DSL.name("uid"), classOf[Integer])
  private val NAME = DSL.field(DSL.name("name"), classOf[String])
  private val DOCKERFILE = DSL.field(DSL.name("dockerfile"), classOf[String])
  private val STATUS = DSL.field(DSL.name("status"), classOf[String])
  private val IMAGE_TAG = DSL.field(DSL.name("image_tag"), classOf[String])
  private val BUILD_NUMBER = DSL.field(DSL.name("build_number"), classOf[Integer])
  private val IS_PUBLIC = DSL.field(DSL.name("is_public"), classOf[java.lang.Boolean])
  private val CREATION_TIME = DSL.field(DSL.name("creation_time"), classOf[Timestamp])
  private val UPDATE_TIME = DSL.field(DSL.name("update_time"), classOf[Timestamp])

  private def newUser(name: String): User = {
    val user = new User
    user.setName(name)
    user.setPassword("123")
    user.setEmail(s"$name@test.com")
    user.setRole(UserRoleEnum.REGULAR)
    user
  }

  private val ownerUser = newUser("ri_owner")
  private val granteeUser = newUser("ri_grantee")
  private val strangerUser = newUser("ri_stranger")

  lazy val accessResource = new RuntimeImageAccessResource()
  lazy val ownerSession = new SessionUser(ownerUser)
  lazy val granteeSession = new SessionUser(granteeUser)
  lazy val strangerSession = new SessionUser(strangerUser)

  private var riid: Integer = _
  private val nonExistentRiid: Integer = 999999

  private def insertRuntimeImage(owner: User, name: String, isPublic: Boolean): Integer = {
    val now = new Timestamp(System.currentTimeMillis())
    getDSLContext
      .insertInto(RUNTIME_IMAGE)
      .set(UID, owner.getUid)
      .set(NAME, name)
      .set(DOCKERFILE, "FROM base\n")
      .set(STATUS, "READY")
      .set(IMAGE_TAG, "registry/texera-runtime-image/1:1")
      .set(BUILD_NUMBER, Integer.valueOf(1))
      .set(IS_PUBLIC, java.lang.Boolean.valueOf(isPublic))
      .set(CREATION_TIME, now)
      .set(UPDATE_TIME, now)
      .returning(RIID)
      .fetchOne()
      .get(RIID)
  }

  private def setPublic(target: Integer, isPublic: Boolean): Unit =
    getDSLContext
      .update(RUNTIME_IMAGE)
      .set(IS_PUBLIC, java.lang.Boolean.valueOf(isPublic))
      .where(RIID.eq(target))
      .execute()

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    initializeDBAndReplaceDSLContext()

    val userDao = new UserDao(getDSLContext.configuration())
    userDao.insert(ownerUser)
    userDao.insert(granteeUser)
    userDao.insert(strangerUser)

    riid = insertRuntimeImage(ownerUser, "image-under-test", isPublic = false)
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    // every case starts private, with no grants
    getDSLContext.deleteFrom(ACCESS).execute()
    setPublic(riid, isPublic = false)
  }

  override protected def afterAll(): Unit = {
    try shutdownDB()
    finally super.afterAll()
  }

  // ===========================================================================
  // grantAccess
  // ===========================================================================

  "grantAccess" should "add a grantee that appears in the access list" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)

    val entries = accessResource.getAccessList(ownerSession, riid)
    entries should have size 1
    entries.head.email shouldBe granteeUser.getEmail
    entries.head.privilege shouldBe PrivilegeEnum.READ
  }

  it should "update the privilege in place when the same grantee is granted twice" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.WRITE)

    val entries = accessResource.getAccessList(ownerSession, riid)
    entries should have size 1
    entries.head.privilege shouldBe PrivilegeEnum.WRITE
  }

  it should "reject an email with no account" in {
    a[BadRequestException] should be thrownBy
      accessResource.grantAccess(ownerSession, riid, "nobody@test.com", PrivilegeEnum.READ)
  }

  it should "reject granting to the owner, who already has everything" in {
    a[BadRequestException] should be thrownBy
      accessResource.grantAccess(ownerSession, riid, ownerUser.getEmail, PrivilegeEnum.READ)
  }

  it should "hide the runtime image from a stranger rather than forbid them" in {
    a[NotFoundException] should be thrownBy
      accessResource.grantAccess(strangerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)
  }

  it should "forbid a READ grantee from granting to anyone else" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)

    a[ForbiddenException] should be thrownBy
      accessResource.grantAccess(granteeSession, riid, strangerUser.getEmail, PrivilegeEnum.READ)
  }

  it should "let a WRITE grantee grant to someone else" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.WRITE)
    accessResource.grantAccess(granteeSession, riid, strangerUser.getEmail, PrivilegeEnum.READ)

    accessResource.getAccessList(ownerSession, riid).map(_.email) should contain(
      strangerUser.getEmail
    )
  }

  it should "not let a public runtime image be shared by just anyone" in {
    // public confers read, and read is not enough to change who else may use it
    setPublic(riid, isPublic = true)

    a[ForbiddenException] should be thrownBy
      accessResource.grantAccess(strangerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)
  }

  // ===========================================================================
  // revokeAccess
  // ===========================================================================

  "revokeAccess" should "remove the grantee from the list" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)
    accessResource.revokeAccess(ownerSession, riid, granteeUser.getEmail)

    accessResource.getAccessList(ownerSession, riid) shouldBe empty
  }

  it should "let a read-only grantee revoke their own access" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)
    accessResource.revokeAccess(granteeSession, riid, granteeUser.getEmail)

    accessResource.getAccessList(ownerSession, riid) shouldBe empty
  }

  it should "forbid a read-only grantee from revoking someone else" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)
    accessResource.grantAccess(ownerSession, riid, strangerUser.getEmail, PrivilegeEnum.READ)

    a[ForbiddenException] should be thrownBy
      accessResource.revokeAccess(granteeSession, riid, strangerUser.getEmail)
  }

  // ===========================================================================
  // getOwner / getAccessList visibility
  // ===========================================================================

  "getOwner" should "report the owner's email to someone it is shared with" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)
    accessResource.getOwner(granteeSession, riid) shouldBe ownerUser.getEmail
  }

  it should "report the owner's email for a public runtime image" in {
    setPublic(riid, isPublic = true)
    accessResource.getOwner(strangerSession, riid) shouldBe ownerUser.getEmail
  }

  it should "hide a private runtime image from a stranger" in {
    a[NotFoundException] should be thrownBy accessResource.getOwner(strangerSession, riid)
  }

  it should "report a runtime image that does not exist as absent" in {
    a[NotFoundException] should be thrownBy accessResource.getOwner(ownerSession, nonExistentRiid)
  }

  // ===========================================================================
  // RuntimeImageResource access helpers
  // ===========================================================================

  "hasReadAccess" should "hold for the owner" in {
    RuntimeImageResource.hasReadAccess(riid, ownerUser.getUid) shouldBe true
  }

  it should "not hold for a stranger while the runtime image is private" in {
    RuntimeImageResource.hasReadAccess(riid, strangerUser.getUid) shouldBe false
  }

  it should "hold for everyone once the runtime image is public" in {
    setPublic(riid, isPublic = true)
    RuntimeImageResource.hasReadAccess(riid, strangerUser.getUid) shouldBe true
  }

  it should "hold for a READ grantee" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)
    RuntimeImageResource.hasReadAccess(riid, granteeUser.getUid) shouldBe true
  }

  "hasWriteAccess" should "hold for a WRITE grantee but not a READ one" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)
    RuntimeImageResource.hasWriteAccess(riid, granteeUser.getUid) shouldBe false

    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.WRITE)
    RuntimeImageResource.hasWriteAccess(riid, granteeUser.getUid) shouldBe true
  }

  it should "not follow from a runtime image being public" in {
    // publishing offers an image to run, not a Dockerfile for anyone to rewrite
    setPublic(riid, isPublic = true)
    RuntimeImageResource.hasWriteAccess(riid, strangerUser.getUid) shouldBe false
  }

  "readyImageFor" should "give a stranger nothing while the runtime image is private" in {
    RuntimeImageResource.readyImageFor(riid, strangerUser.getUid) shouldBe None
  }

  it should "give the image to a grantee, which is what starting a unit needs" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.READ)
    RuntimeImageResource.readyImageFor(riid, granteeUser.getUid) shouldBe
      Some("registry/texera-runtime-image/1:1")
  }

  it should "give the image to anyone once the runtime image is public" in {
    setPublic(riid, isPublic = true)
    RuntimeImageResource.readyImageFor(riid, strangerUser.getUid) shouldBe
      Some("registry/texera-runtime-image/1:1")
  }

  it should "give nothing for a build that has not succeeded" in {
    val pending = insertRuntimeImage(ownerUser, "still-building", isPublic = true)
    getDSLContext.update(RUNTIME_IMAGE).set(STATUS, "BUILDING").where(RIID.eq(pending)).execute()

    RuntimeImageResource.readyImageFor(pending, ownerUser.getUid) shouldBe None
  }

  "isOwner" should "distinguish the owner from a WRITE grantee" in {
    accessResource.grantAccess(ownerSession, riid, granteeUser.getEmail, PrivilegeEnum.WRITE)

    RuntimeImageResource.isOwner(riid, ownerUser.getUid) shouldBe true
    RuntimeImageResource.isOwner(riid, granteeUser.getUid) shouldBe false
  }
}
