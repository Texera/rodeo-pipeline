/**
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

import { WorkflowState, type SharedWorkflowSession, type Workflow } from "@texera/sdk";
import { ToolError } from "./errors";

/**
 * An open workflow being edited.
 *
 * Edits accumulate in memory and are written back by `workflow_save` in one
 * `persist` call. `openedLastModifiedTime` is the optimistic-concurrency token:
 * the workspace UI edits through a Yjs document and persists from the browser,
 * so if that timestamp moved while the session was open, someone else (or the
 * user's own open tab) has written since — and a blind save would discard their
 * work.
 *
 * When `live` is present the session is also joined to the workflow's
 * shared-editing room, and every mutation is mirrored there so the user watches
 * it happen. The in-memory `state` stays authoritative for this client either
 * way, so the editing tools behave identically with or without a room.
 */
export interface EditSession {
  wid: number;
  name: string;
  description: string;
  isPublic: boolean;
  readonly: boolean;
  state: WorkflowState;
  openedLastModifiedTime?: number;
  /** True once any mutating tool has touched `state`. */
  dirty: boolean;
  openedAt: number;
  /** The shared-editing room, when live co-editing is on and the join succeeded. */
  live?: SharedWorkflowSession;
}

export class EditSessionStore {
  private sessions = new Map<number, EditSession>();
  /** The workflow the edit tools act on when no `wid` is given. */
  private activeWid?: number;

  open(workflow: Workflow, readonly: boolean, live?: SharedWorkflowSession): EditSession {
    const state = new WorkflowState();
    state.setWorkflowContent(workflow.content);

    const session: EditSession = {
      wid: workflow.wid,
      name: workflow.name,
      description: workflow.description ?? "",
      isPublic: workflow.isPublic ?? false,
      readonly,
      state,
      openedLastModifiedTime: workflow.lastModifiedTime,
      dirty: false,
      openedAt: Date.now(),
      live,
    };

    const previous = this.sessions.get(workflow.wid);
    if (previous) {
      previous.state.destroy();
      // Re-opening replaces the room too, unless the caller handed the same one
      // back — leaving the old connection would show two Claudes in the list.
      if (previous.live && previous.live !== live) previous.live.destroy();
    }
    this.sessions.set(workflow.wid, session);
    this.activeWid = workflow.wid;
    return session;
  }

  /**
   * The session the edit tools operate on. Omitting `wid` uses the most
   * recently opened workflow, which is what a conversation implies by
   * "add a filter" right after opening one.
   */
  require(wid?: number): EditSession {
    const targetWid = wid ?? this.activeWid;
    if (targetWid === undefined) {
      throw new ToolError(
        "No workflow is open. Call workflow_open with a workflow id first (workflow_list shows the ids)."
      );
    }
    const session = this.sessions.get(targetWid);
    if (!session) {
      throw new ToolError(`Workflow ${targetWid} is not open in this session. Call workflow_open(${targetWid}) first.`);
    }
    this.activeWid = targetWid;
    return session;
  }

  /** Like {@link require}, and additionally refuses read-only workflows. */
  requireWritable(wid?: number): EditSession {
    const session = this.require(wid);
    if (session.readonly) {
      throw new ToolError(
        `You have read-only access to workflow ${session.wid} ("${session.name}"). ` +
          `Use workflow_duplicate to get your own editable copy.`
      );
    }
    return session;
  }

  get(wid: number): EditSession | undefined {
    return this.sessions.get(wid);
  }

  list(): EditSession[] {
    return [...this.sessions.values()];
  }

  close(wid: number): boolean {
    const session = this.sessions.get(wid);
    if (!session) return false;
    session.state.destroy();
    session.live?.destroy();
    this.sessions.delete(wid);
    if (this.activeWid === wid) this.activeWid = undefined;
    return true;
  }

  markSaved(session: EditSession, lastModifiedTime: number | undefined): void {
    session.dirty = false;
    session.openedLastModifiedTime = lastModifiedTime;
  }

  /** Leaves every room, so the participant list does not keep a ghost. */
  closeAll(): void {
    for (const wid of [...this.sessions.keys()]) this.close(wid);
  }
}

/**
 * Records an edit: marks the session dirty and, when it is live, pushes the new
 * graph into the shared-editing room and says what is being worked on.
 *
 * Pushing the whole graph rather than the individual change keeps this correct
 * without a second mutation path — auto-layout moves operators the caller never
 * named, so a per-mutation mirror would drift from `state` immediately.
 */
export function recordEdit(session: EditSession, activity: { editing?: string; highlighted?: string[] } = {}): void {
  session.dirty = true;
  if (!session.live?.connected) return;
  session.live.replaceContent(session.state.getWorkflowContent());
  // At most one operator is ever shown as being worked on. A client that edits
  // through an API has no pointer to follow, so the halo is the only signal of
  // where it is; more than one at a time makes that signal meaningless.
  const editing = activity.editing ?? activity.highlighted?.[0];
  session.live.publishPresence({
    editing,
    highlighted: editing === undefined ? undefined : [editing],
  });
}
