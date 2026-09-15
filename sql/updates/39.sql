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

-- Renames "environment" to "runtime image" throughout.
--
-- The old name collided with three unrelated things in this codebase -- a Python virtual
-- environment, an Angular build environment, and a process environment variable -- and
-- named the Dockerfile rather than the thing a computing unit actually runs. "Runtime
-- image" names the image.
--
-- The primary key becomes riid for the same reason: eid was already the primary key of
-- workflow_executions.
--
-- Image references already stored in image_tag are left exactly as they are. They are
-- literal strings pointing at layers that exist in the registry under the old path, and
-- rewriting them would orphan every image built before this migration -- including any a
-- computing unit is currently running. New builds publish under the new path.

\c texera_db

SET search_path TO texera_db;

BEGIN;

ALTER TABLE environment RENAME TO runtime_image;
ALTER TABLE runtime_image RENAME COLUMN eid TO riid;

ALTER TABLE environment_user_access RENAME TO runtime_image_user_access;
ALTER TABLE runtime_image_user_access RENAME COLUMN eid TO riid;

-- Renaming a table leaves its sequence, indexes and constraints under their old names.
-- They keep working either way; renaming them keeps the schema readable.
ALTER SEQUENCE IF EXISTS environment_eid_seq RENAME TO runtime_image_riid_seq;

ALTER INDEX IF EXISTS idx_environment_uid RENAME TO idx_runtime_image_uid;
ALTER INDEX IF EXISTS idx_environment_user_access_uid
    RENAME TO idx_runtime_image_user_access_uid;

ALTER INDEX IF EXISTS environment_pkey RENAME TO runtime_image_pkey;
ALTER INDEX IF EXISTS environment_uid_name_key RENAME TO runtime_image_uid_name_key;
ALTER INDEX IF EXISTS environment_user_access_pkey RENAME TO runtime_image_user_access_pkey;

ALTER TABLE runtime_image
    RENAME CONSTRAINT environment_uid_fkey TO runtime_image_uid_fkey;
ALTER TABLE runtime_image_user_access
    RENAME CONSTRAINT environment_user_access_eid_fkey TO runtime_image_user_access_riid_fkey;
ALTER TABLE runtime_image_user_access
    RENAME CONSTRAINT environment_user_access_uid_fkey TO runtime_image_user_access_uid_fkey;

COMMIT;
