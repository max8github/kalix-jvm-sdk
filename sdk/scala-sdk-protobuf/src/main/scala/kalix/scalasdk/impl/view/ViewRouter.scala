/*
 * Copyright 2024 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kalix.scalasdk.impl.view

import kalix.scalasdk.view.View

/**
 * INTERNAL API, but used by generated code.
 */
abstract class ViewUpdateRouter

/**
 * INTERNAL API, but used by generated code.
 */
abstract class ViewRouter[S, V <: View[S]](val view: V) extends ViewUpdateRouter {
  def handleUpdate(commandName: String, state: S, event: Any): View.UpdateEffect[S]
}

/**
 * INTERNAL API, but used by generated code.
 */
abstract class ViewMultiTableRouter extends ViewUpdateRouter {
  def viewRouter(eventName: String): ViewRouter[_, _]
}
