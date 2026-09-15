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

-- Sharing for environments, following dataset and model: an is_public flag for "anyone
-- may use this", and a per-user access table for "these people may use this".

\c texera_db

SET search_path TO texera_db;

BEGIN;

-- FALSE, unlike dataset.is_public and model.is_public, which default TRUE. An
-- environment is an image built from instructions its owner wrote, so publishing it
-- offers other people something to execute; that is a choice to make deliberately
-- rather than the state an environment starts in.
ALTER TABLE environment
    ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS environment_user_access
(
    eid       INT NOT NULL,
    uid       INT NOT NULL,
    privilege privilege_enum NOT NULL DEFAULT 'NONE',
    PRIMARY KEY (eid, uid),
    FOREIGN KEY (eid) REFERENCES environment (eid) ON DELETE CASCADE,
    FOREIGN KEY (uid) REFERENCES "user" (uid) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_environment_user_access_uid
    ON environment_user_access (uid);

COMMIT;
