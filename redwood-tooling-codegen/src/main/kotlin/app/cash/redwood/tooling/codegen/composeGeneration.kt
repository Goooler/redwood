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
package app.cash.redwood.tooling.codegen

import app.cash.redwood.tooling.schema.FqType
import app.cash.redwood.tooling.schema.Modifier
import app.cash.redwood.tooling.schema.ProtocolWidget.ProtocolTrait
import app.cash.redwood.tooling.schema.Schema
import app.cash.redwood.tooling.schema.Widget
import app.cash.redwood.tooling.schema.Widget.Children
import app.cash.redwood.tooling.schema.Widget.Event
import app.cash.redwood.tooling.schema.Widget.Property
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode

/*
@Composable
@SunspotComposable
@OptIn(RedwoodCodegenApi::class)
fun Row(
  margin: Margin = Margin.Zero,
  overflow: Overflow = Overflow.Clip,
  modifier: Modifier = Modifier,
  children: @Composable @SunspotComposable RowScope.() -> Unit,
): Unit {
  RedwoodComposeNode<SunspotWidgetFactoryProvider<Any>, Row<Any>, Any>(
    factory = { it.RedwoodLayout.Row() },
    update = {
      set(modifier, WidgetNode.SetModifiers)
      set(margin) { recordChanged(); widget.margin(it) }
      set(overflow) { recordChanged(); widget.overflow(it) }
    },
    content = {
      Children(Row<Any>::children) {
        RowScopeImpl.children()
      }
    },
  )
}
*/
internal fun generateComposable(
  schema: Schema,
  widget: Widget,
): FileSpec {
  val widgetType = schema.widgetType(widget).parameterizedBy(ANY)
  val flatName = widget.type.flatName
  return FileSpec.builder(schema.composePackage(), flatName)
    .addAnnotation(suppressDeprecations)
    .addFunction(
      FunSpec.builder(flatName)
        .addAnnotation(ComposeRuntime.Composable)
        .optIn(Redwood.RedwoodCodegenApi)
        .maybeAddKDoc(widget.documentation)
        .maybeAddDeprecation(widget.deprecation)
        .apply {
          // Set the layout modifier as the last non-child lambda in the function signature.
          // This ensures you can still use trailing lambda syntax.
          val modifierIndex = widget.traits.indexOfLast { it !is Children } + 1

          var index = 0
          while (true) {
            if (index == modifierIndex) {
              addParameter(
                ParameterSpec.builder("modifier", Redwood.Modifier)
                  .defaultValue("%T", Redwood.Modifier)
                  .build(),
              )
            }
            if (index >= widget.traits.size) {
              break
            }
            val trait = widget.traits[index]
            addParameter(
              when (trait) {
                is Property -> {
                  ParameterSpec.builder(trait.name, trait.type.asTypeName())
                    .maybeAddKDoc(trait.documentation)
                    .maybeDefaultValue(trait.defaultExpression)
                    .build()
                }

                is Event -> {
                  ParameterSpec.builder(trait.name, trait.lambdaType)
                    .maybeAddKDoc(trait.documentation)
                    .maybeDefaultValue(trait.defaultExpression)
                    .build()
                }

                is Children -> {
                  val scope = trait.scope?.let { ClassName(schema.composePackage(), it.flatName) }
                  ParameterSpec.builder(trait.name, composableLambda(scope))
                    .maybeAddKDoc(trait.documentation)
                    .maybeDefaultValue(trait.defaultExpression)
                    .build()
                }

                is ProtocolTrait -> throw AssertionError()
              },
            )
            index++
          }

          val updateLambda = CodeBlock.builder()

          val childrenLambda = CodeBlock.builder()
          for (trait in widget.traits) {
            when (trait) {
              is Property,
              is Event,
              -> {
                updateLambda.add("set(%1N) { recordChanged(); widget.%1N(it) }\n", trait.name)
              }

              is Children -> {
                childrenLambda.apply {
                  add("Children(%T::%N) {\n", widgetType, trait.name)
                  indent()
                  trait.scope?.let { scope ->
                    add("%T.", ClassName(schema.composePackage(), scope.flatName + "Impl"))
                  }
                  add("%N()\n", trait.name)
                  unindent()
                  add("}\n")
                }
              }

              is ProtocolTrait -> throw AssertionError()
            }
          }

          // Set all properties then the modifiers.
          updateLambda.add("set(modifier, %T.SetModifiers)\n", RedwoodCompose.WidgetNode)

          val arguments = listOf(
            CodeBlock.of("factory = { it.%N.%N() }", schema.type.flatName, flatName),
            CodeBlock.builder()
              .add("update = {\n")
              .indent()
              .add(updateLambda.build())
              .unindent()
              .add("}")
              .build(),
            CodeBlock.builder()
              .add("content = {\n")
              .indent()
              .add(childrenLambda.build())
              .unindent()
              .add("}")
              .build(),
          )

          addStatement(
            "%M<%T, %T, %T>(%L)",
            RedwoodCompose.RedwoodComposeNode,
            schema.getWidgetFactoryOwnerType().parameterizedBy(ANY),
            widgetType,
            ANY,
            arguments.joinToCode(",\n", "\n", ",\n"),
          )
        }
        .build(),
    )
    .build()
}

/*
interface RowScope {
  @Stable
  fun Modifier.something(...): Modifier {
    return then(SomethingImpl(...))
  }
}

internal object RowScopeImpl : RowScope
*/
internal fun generateModifierScope(schema: Schema, scope: FqType): FileSpec {
  val scopeName = scope.flatName
  val scopeType = ClassName(schema.composePackage(), scopeName)
  val modifiers = schema.modifiers.filter { scope in it.scopes }
  return FileSpec.builder(scopeType)
    .addAnnotation(suppressDeprecations)
    .apply {
      addType(
        TypeSpec.interfaceBuilder(scopeType)
          .addAnnotation(Redwood.LayoutScopeMarker)
          .apply {
            for (modifier in modifiers) {
              addFunction(generateModifierFunction(schema, modifier))
            }
          }
          .build(),
      )
      addType(
        TypeSpec.objectBuilder(scopeName + "Impl")
          .addModifiers(INTERNAL)
          .addSuperinterface(scopeType)
          .build(),
      )
    }
    .build()
}

/*
@Stable
fun Modifier.something(...): Modifier {
  return then(SomethingImpl(...))
}
*/
internal fun generateUnscopedModifiers(schema: Schema): FileSpec? {
  if (schema.unscopedModifiers.isEmpty()) return null

  return FileSpec.builder(schema.composePackage(), "unscoped")
    .addAnnotation(suppressDeprecations)
    .apply {
      for (modifier in schema.unscopedModifiers) {
        addFunction(generateModifierFunction(schema, modifier))
      }
    }
    .build()
}

/*
internal class SomethingImpl(...): Something {
  public override fun equals(other: Any?): Boolean = ...
  public override fun hashCode(): Int = ...
  public override fun toString(): String = ...
}
*/
internal fun generateModifierImpls(schema: Schema): FileSpec? {
  if (schema.modifiers.isEmpty()) return null

  return FileSpec.builder(schema.composePackage(), "modifier")
    .addAnnotation(suppressDeprecations)
    .apply {
      for (modifier in schema.modifiers) {
        addType(generateModifierImpl(schema, modifier))
      }
    }
    .build()
}

private fun generateModifierFunction(
  schema: Schema,
  modifier: Modifier,
): FunSpec {
  val simpleName = modifier.type.flatName
  return FunSpec.builder(simpleName.replaceFirstChar(Char::lowercaseChar))
    .addAnnotation(ComposeRuntime.Stable)
    .receiver(Redwood.Modifier)
    .returns(Redwood.Modifier)
    .maybeAddKDoc(modifier.documentation)
    .apply {
      val arguments = mutableListOf<CodeBlock>()
      for (property in modifier.properties) {
        arguments += CodeBlock.of("%N", property.name)

        addParameter(
          ParameterSpec.builder(property.name, property.type.asTypeName())
            .maybeAddKDoc(property.documentation)
            .maybeDefaultValue(property.defaultExpression)
            .build(),
        )
      }

      val typeName = schema.modifierImpl(modifier)
      if (arguments.isEmpty()) {
        addStatement("return then(%T)", typeName)
      } else {
        addStatement("return then(%T(%L))", typeName, arguments.joinToCode())
      }
    }
    .build()
}

private fun generateModifierImpl(
  schema: Schema,
  modifier: Modifier,
): TypeSpec {
  val typeName = schema.modifierImpl(modifier)
  val typeBuilder = if (modifier.properties.isEmpty()) {
    TypeSpec.objectBuilder(typeName)
  } else {
    TypeSpec.classBuilder(typeName)
      .apply {
        val primaryConstructor = FunSpec.constructorBuilder()
        for (property in modifier.properties) {
          val propertyType = property.type.asTypeName()
          primaryConstructor.addParameter(property.name, propertyType)
          addProperty(
            PropertySpec.builder(property.name, propertyType)
              .addModifiers(OVERRIDE)
              .initializer("%N", property.name)
              .build(),
          )
        }
        primaryConstructor(primaryConstructor.build())
      }
  }

  return typeBuilder
    .addModifiers(INTERNAL)
    .addSuperinterface(schema.modifierType(modifier))
    .addFunction(modifierEquals(schema, modifier))
    .addFunction(modifierHashCode(modifier))
    .addFunction(modifierToString(modifier))
    .build()
}

private fun Schema.modifierImpl(modifier: Modifier): ClassName {
  return ClassName(composePackage(), modifier.type.flatName + "Impl")
}
