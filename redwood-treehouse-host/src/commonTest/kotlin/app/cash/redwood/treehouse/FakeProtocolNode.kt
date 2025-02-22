/*
 * Copyright (C) 2023 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.redwood.treehouse

import app.cash.redwood.RedwoodCodegenApi
import app.cash.redwood.protocol.ChildrenTag
import app.cash.redwood.protocol.Event
import app.cash.redwood.protocol.EventSink
import app.cash.redwood.protocol.EventTag
import app.cash.redwood.protocol.Id
import app.cash.redwood.protocol.PropertyChange
import app.cash.redwood.protocol.WidgetTag
import app.cash.redwood.protocol.host.ProtocolChildren
import app.cash.redwood.protocol.host.ProtocolNode
import kotlinx.serialization.json.JsonPrimitive

/**
 * This supports [FakeWidget] and its [FakeWidget.label] property.
 */
@RedwoodCodegenApi
internal class FakeProtocolNode(
  id: Id,
  tag: WidgetTag,
) : ProtocolNode<FakeWidget>(id, tag) {
  override val widget = FakeWidget()

  override fun apply(change: PropertyChange, eventSink: EventSink) {
    widget.label = (change.value as JsonPrimitive).content
    widget.onClick = {
      eventSink.sendEvent(Event(Id(1), EventTag(1)))
    }
  }

  override fun children(tag: ChildrenTag): ProtocolChildren<FakeWidget>? {
    error("unexpected call")
  }

  override fun visitIds(block: (Id) -> Unit) {
    block(id)
  }
}
