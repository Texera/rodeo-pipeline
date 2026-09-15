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

import { Injectable } from "@angular/core";
import { BehaviorSubject, Observable } from "rxjs";

/**
 * Tracks whether the workspace is in "AI edit mode": everything except the
 * canvas, the result panel and the chatbot is hidden, so the user and the
 * assistant share an uncluttered view of the workflow.
 *
 * The state lives in a service rather than in the workspace component because
 * the toggle sits in the menu and the panels that react to it are siblings.
 */
@Injectable({ providedIn: "root" })
export class AiEditModeService {
  private readonly active = new BehaviorSubject<boolean>(false);

  public getActiveStream(): Observable<boolean> {
    return this.active.asObservable();
  }

  public isActive(): boolean {
    return this.active.value;
  }

  public toggle(): void {
    this.active.next(!this.active.value);
  }

  public exit(): void {
    if (this.active.value) {
      this.active.next(false);
    }
  }
}
