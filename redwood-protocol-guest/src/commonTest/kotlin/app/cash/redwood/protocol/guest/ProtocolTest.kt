/*
 * Copyright (C) 2021 Square, Inc.
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
package app.cash.redwood.protocol.guest

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.redwood.compose.WidgetVersion
import app.cash.redwood.layout.compose.Column
import app.cash.redwood.layout.compose.Row
import app.cash.redwood.protocol.Change
import app.cash.redwood.protocol.ChildrenChange
import app.cash.redwood.protocol.ChildrenTag
import app.cash.redwood.protocol.Create
import app.cash.redwood.protocol.Event
import app.cash.redwood.protocol.EventTag
import app.cash.redwood.protocol.Id
import app.cash.redwood.protocol.ModifierChange
import app.cash.redwood.protocol.PropertyChange
import app.cash.redwood.protocol.PropertyTag
import app.cash.redwood.protocol.RedwoodVersion
import app.cash.redwood.protocol.WidgetTag
import app.cash.redwood.testing.TestRedwoodComposition
import app.cash.redwood.ui.Cancellable
import app.cash.redwood.ui.OnBackPressedCallback
import app.cash.redwood.ui.OnBackPressedDispatcher
import app.cash.redwood.ui.UiConfiguration
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.message
import com.example.redwood.testing.compose.Button
import com.example.redwood.testing.compose.Button2
import com.example.redwood.testing.compose.TestRow
import com.example.redwood.testing.compose.Text
import com.example.redwood.testing.protocol.guest.TestSchemaProtocolBridge
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class ProtocolTest {
  // Use latest guest version as the host version to avoid any compatibility behavior.
  private val latestVersion = guestRedwoodVersion

  @Test fun widgetVersionPropagated() = runTest {
    val composition = ProtocolRedwoodComposition(
      scope = this + BroadcastFrameClock(),
      bridge = TestSchemaProtocolBridge.create(latestVersion),
      changesSink = ::error,
      widgetVersion = 22U,
      onBackPressedDispatcher = object : OnBackPressedDispatcher {
        override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable {
          return object : Cancellable {
            override fun cancel() = Unit
          }
        }
      },
      saveableStateRegistry = null,
      uiConfigurations = MutableStateFlow(UiConfiguration()),
    )

    var actualDisplayVersion = 0U
    composition.setContent {
      actualDisplayVersion = WidgetVersion
    }
    composition.cancel()

    assertThat(actualDisplayVersion).isEqualTo(22U)
  }

  @Test fun protocolChangeOrder() = runTest {
    val (composition) = testProtocolComposition()

    composition.setContent {
      TestRow {
        Text("hey")
        TestRow {
          Text("hello")
        }
      }
    }

    assertThat(composition.awaitSnapshot()).isEqualTo(
      listOf(
        // Row
        Create(Id(1), WidgetTag(1)),
        ModifierChange(Id(1)),
        // Text
        Create(Id(2), WidgetTag(3)),
        // text
        PropertyChange(Id(2), PropertyTag(1), JsonPrimitive("hey")),
        ModifierChange(Id(2)),
        ChildrenChange.Add(Id(1), ChildrenTag(1), Id(2), 0),
        // Row
        Create(Id(3), WidgetTag(1)),
        ModifierChange(Id(3)),
        // Text
        Create(Id(4), WidgetTag(3)),
        // text
        PropertyChange(Id(4), PropertyTag(1), JsonPrimitive("hello")),
        ModifierChange(Id(4)),
        ChildrenChange.Add(Id(3), ChildrenTag(1), Id(4), 0),
        ChildrenChange.Add(Id(1), ChildrenTag(1), Id(3), 1),
        ChildrenChange.Add(Id.Root, ChildrenTag.Root, Id(1), 0),
      ),
    )
  }

  @Test fun protocolAlwaysSendsInitialLambdaPresence() = runTest {
    val (composition) = testProtocolComposition()
    composition.setContent {
      Button("hi", onClick = null)
      Button("hi", onClick = {})
      Button2("hi", onClick = {})
    }

    assertThat(composition.awaitSnapshot()).isEqualTo(
      listOf(
        // Button
        Create(Id(1), WidgetTag(4)),
        // text
        PropertyChange(Id(1), PropertyTag(1), JsonPrimitive("hi")),
        // onClick
        PropertyChange(Id(1), PropertyTag(2), JsonPrimitive(false)),
        ModifierChange(Id(1)),
        ChildrenChange.Add(Id.Root, ChildrenTag.Root, Id(1), 0),
        // Button
        Create(Id(2), WidgetTag(4)),
        // text
        PropertyChange(Id(2), PropertyTag(1), JsonPrimitive("hi")),
        // onClick
        PropertyChange(Id(2), PropertyTag(2), JsonPrimitive(true)),
        ModifierChange(Id(2)),
        ChildrenChange.Add(Id.Root, ChildrenTag.Root, Id(2), 1),
        // Button2
        Create(Id(3), WidgetTag(7)),
        // text
        PropertyChange(Id(3), PropertyTag(1), JsonPrimitive("hi")),
        // onClick
        PropertyChange(Id(3), PropertyTag(2), JsonPrimitive(true)),
        ModifierChange(Id(3)),
        ChildrenChange.Add(Id.Root, ChildrenTag.Root, Id(3), 2),
      ),
    )
  }

  @Test fun protocolSkipsNullableLambdaChangeOfSamePresence() = runTest {
    val (composition, bridge) = testProtocolComposition()

    var state by mutableStateOf(0)
    composition.setContent {
      Button(
        "state: $state",
        onClick = when (state) {
          0 -> {
            { state = 1 }
          }

          1 -> {
            { state = 2 }
          }

          2 -> {
            null
          }

          3 -> {
            null
          }

          else -> fail()
        },
      )
    }

    assertThat(composition.awaitSnapshot()).isEqualTo(
      listOf(
        // Button
        Create(Id(1), WidgetTag(4)),
        // text
        PropertyChange(Id(1), PropertyTag(1), JsonPrimitive("state: 0")),
        // onClick
        PropertyChange(Id(1), PropertyTag(2), JsonPrimitive(true)),
        ModifierChange(Id(1)),
        ChildrenChange.Add(Id.Root, ChildrenTag.Root, Id(1), 0),
      ),
    )

    // Invoke the onClick lambda to move the state from 0 to 1.
    bridge.sendEvent(Event(Id(1), EventTag(2)))

    assertThat(composition.awaitSnapshot()).isEqualTo(
      listOf(
        // text
        PropertyChange(Id(1), PropertyTag(1), JsonPrimitive("state: 1")),
      ),
    )

    // Invoke the onClick lambda to move the state from 1 to 2.
    bridge.sendEvent(Event(Id(1), EventTag(2)))

    assertThat(composition.awaitSnapshot()).isEqualTo(
      listOf(
        // text
        PropertyChange(Id(1), PropertyTag(1), JsonPrimitive("state: 2")),
        // text
        PropertyChange(Id(1), PropertyTag(2), JsonPrimitive(false)),
      ),
    )

    // Manually advance state from 2 to 3 to test null to null case.
    state = 3

    assertThat(composition.awaitSnapshot()).isEqualTo(
      listOf(
        // text
        PropertyChange(Id(1), PropertyTag(1), JsonPrimitive("state: 3")),
      ),
    )
  }

  @Test fun protocolSkipsNonNullLambdaChange() = runTest {
    val (composition, bridge) = testProtocolComposition()

    var state by mutableStateOf(0)
    composition.setContent {
      Button2(
        "state: $state",
        onClick = when (state) {
          0 -> {
            { state = 1 }
          }

          1 -> {
            { state = 2 }
          }

          else -> fail()
        },
      )
    }

    assertThat(composition.awaitSnapshot()).isEqualTo(
      listOf(
        // Button2
        Create(Id(1), WidgetTag(7)),
        // text
        PropertyChange(Id(1), PropertyTag(1), JsonPrimitive("state: 0")),
        // onClick
        PropertyChange(Id(1), PropertyTag(2), JsonPrimitive(true)),
        ModifierChange(Id(1)),
        ChildrenChange.Add(Id.Root, ChildrenTag.Root, Id(1), 0),
      ),
    )

    // Invoke the onClick lambda to move the state from 0 to 1.
    bridge.sendEvent(Event(Id(1), EventTag(2)))

    assertThat(composition.awaitSnapshot()).isEqualTo(
      listOf(
        // text
        PropertyChange(Id(1), PropertyTag(1), JsonPrimitive("state: 1")),
      ),
    )
  }

  @Test fun entireSubtreeRemovedLatest() = runTest {
    assertThat(removeSubtree(latestVersion))
      .containsExactly(
        ChildrenChange.Remove(Id.Root, ChildrenTag.Root, 0, 1),
      )
  }

  @Test fun entireSubtreeRemovedOldHostSynthesizesDepthFirstRemoval() = runTest {
    assertThat(removeSubtree(RedwoodVersion("0.9.0")))
      .containsExactly(
        ChildrenChange.Remove(Id(2), ChildrenTag(1), 0, 1, listOf(Id(3))),
        ChildrenChange.Remove(Id(1), ChildrenTag(1), 0, 1, listOf(Id(2))),
        ChildrenChange.Remove(Id.Root, ChildrenTag.Root, 0, 1, listOf(Id(1))),
      )
  }

  private suspend fun TestScope.removeSubtree(hostVersion: RedwoodVersion): List<Change> {
    val (composition, bridge) = testProtocolComposition(hostVersion)

    var clicks = 0
    var remove by mutableStateOf(false)
    composition.setContent {
      if (!remove) {
        Row {
          Column {
            Button("Click?", onClick = { clicks++ })
          }
        }
      }
    }
    composition.awaitSnapshot()
    assertThat(clicks).isEqualTo(0)

    // Ensure the button is present and receiving clicks.
    bridge.sendEvent(Event(Id(3), EventTag(2)))
    assertThat(clicks).isEqualTo(1)

    remove = true
    val removeChanges = composition.awaitSnapshot()

    // If the whole tree was removed, we cannot target the button anymore.
    assertFailure { bridge.sendEvent(Event(Id(3), EventTag(2))) }
      .isInstanceOf<IllegalArgumentException>()
      .message()
      .isEqualTo("Unknown node ID 3 for event with tag 2")
    assertThat(clicks).isEqualTo(1)

    return removeChanges
  }

  private fun TestScope.testProtocolComposition(
    hostVersion: RedwoodVersion = latestVersion,
  ): Pair<TestRedwoodComposition<List<Change>>, ProtocolBridge> {
    val bridge = TestSchemaProtocolBridge.create(hostVersion)
    val composition = TestRedwoodComposition(
      scope = backgroundScope,
      widgetSystem = bridge.widgetSystem,
      container = bridge.root,
    ) {
      bridge.getChangesOrNull() ?: emptyList()
    }
    backgroundScope.coroutineContext.job.invokeOnCompletion {
      composition.cancel()
    }
    return composition to bridge
  }
}
