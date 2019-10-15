package com.juul.stropping

import android.util.Log
import org.kodein.di.AnyToken
import org.kodein.di.Kodein
import org.kodein.di.TypeToken
import org.kodein.di.bindings.InSet
import org.kodein.di.bindings.NoArgSimpleBindingKodein
import org.kodein.di.bindings.SetBinding
import org.kodein.di.generic.provider
import org.kodein.di.generic.singleton
import org.kodein.di.jvmType
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Type
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KType

private fun Kodein.Builder.bindSingle(
    typeToken: TypeToken<Any>,
    tag: Any?,
    isSingleton: Boolean,
    buildName: String,
    build: NoArgSimpleBindingKodein<*>.() -> Any
) {
    val bindingType = if (isSingleton) "singleton" else "provider"
    Log.d("Stropping", "Binding $bindingType for ${typeToken.simpleDispString()} (tag=$tag) with $buildName")
    val binding = when (isSingleton) {
        true -> singleton { build() }
        false -> provider { build() }
    }
    Bind(typeToken, tag) with binding
}

private fun Kodein.Builder.bindIntoSet(
    typeToken: TypeToken<Any>,
    tag: Any?,
    isSingleton: Boolean,
    buildName: String,
    build: NoArgSimpleBindingKodein<*>.() -> Any
) {
    val bindingType = if (isSingleton) "singleton" else "provider"
    Log.d("Stropping", "Binding $bindingType into Set<${typeToken.simpleDispString()}> (tag=$tag) with $buildName")
    val binding = when (isSingleton) {
        true -> singleton { build() }
        false -> provider { build() }
    }

    val setType = Set::class.java.parameterize(typeToken.jvmType)
    @Suppress("UNCHECKED_CAST")
    val setTypeToken = createTypeToken(setType) as TypeToken<Set<Any>>

    try {
        Bind(typeToken, tag).InSet(setTypeToken) with binding
    } catch (e: IllegalStateException) {
        // Set up the Set<T>, then retry
        Log.d("Stropping", "Failed to bind. Creating Set<...> bindings.")
        Bind(tag) from SetBinding(AnyToken, typeToken, setTypeToken)
        Bind(typeToken, tag).InSet(setTypeToken) with binding
    }
}

internal fun Kodein.Builder.bindIntoMap(
    typeToken: TypeToken<Any>,
    tag: Any?,
    intoMap: Multibindings.ToMap,
    isSingleton: Boolean,
    buildName: String,
    build: NoArgSimpleBindingKodein<*>.() -> Any
) {
    val bindingType = if (isSingleton) "singleton" else "provider"
    Log.d("Stropping", "Binding $bindingType into Map<${intoMap.keyType}, ${typeToken.simpleDispString()}> (tag=$tag) with $buildName")
    val binding = when (isSingleton) {
        true -> singleton { intoMap.keyValue to build() }
        false -> provider { intoMap.keyValue to build() }
    }

    val pairType = Pair::class.java.parameterize(intoMap.keyType, typeToken.jvmType)
    val setType = Set::class.java.parameterize(pairType)
    val directMapType = Map::class.java.parameterize(intoMap.keyType, typeToken.jvmType)
    val providerType = Provider::class.java.parameterize(typeToken.jvmType)
    val indirectMapType = Map::class.java.parameterize(intoMap.keyType, providerType)

    val pairTypeToken = createTypeToken(pairType)
    @Suppress("UNCHECKED_CAST")
    val setTypeToken = createTypeToken(setType) as TypeToken<Set<Any>>
    val mapTypeToken = createTypeToken(directMapType)
    val indirectMapTypeToken = createTypeToken(indirectMapType)

    try {
        Bind(pairTypeToken, tag).InSet(setTypeToken) with binding
    } catch (e: IllegalStateException) {
        // Set up the Set<Pair<K, T>>, Map<K, T>, and Map<K, Provider<T>>, then retry
        Log.d("Stropping", "Failed to bind. Creating Set<...> and Map<...> bindings.")
        Bind(tag) from SetBinding(AnyToken, pairTypeToken, setTypeToken)
        Bind(mapTypeToken, tag) with provider {
            @Suppress("UNCHECKED_CAST")
            (Instance(setTypeToken, tag) as Set<Pair<*, *>>).toMap()
        }
        Bind(indirectMapTypeToken, tag) with provider {
            (Instance(mapTypeToken, tag) as Map<*, *>).mapValues { (_, v) ->
                Provider { v }
            }
        }
        Bind(pairTypeToken, tag).InSet(setTypeToken) with binding
    }
}

private fun Kodein.Builder.bind(
    typeToken: TypeToken<Any>,
    annotations: List<Annotation>,
    buildName: String,
    multibindings: Multibindings,
    build: NoArgSimpleBindingKodein<*>.() -> Any
) {
    val tag = getTag(annotations)
    val isSingleton = annotations.any { it is Singleton }
    when (multibindings) {
        is Multibindings.Single ->
            bindSingle(typeToken, tag, isSingleton, buildName, build)
        is Multibindings.ToSet ->
            bindIntoSet(typeToken, tag, isSingleton, buildName, build)
        is Multibindings.ToMap ->
            bindIntoMap(typeToken, tag, multibindings, isSingleton, buildName, build)
    }
}

internal fun Kodein.Builder.bind(
    type: Type,
    element: AnnotatedElement?,
    buildName: String,
    multibindings: Multibindings,
    build: NoArgSimpleBindingKodein<*>.() -> Any
) = bind(
    createTypeToken(type),
    element?.annotations?.toList().orEmpty(),
    buildName,
    multibindings,
    build
)

internal fun Kodein.Builder.bind(
    type: KType,
    element: KAnnotatedElement?,
    builderName: String,
    multibindings: Multibindings,
    build: NoArgSimpleBindingKodein<*>.() -> Any
) = bind(
    createTypeToken(type),
    element?.annotations.orEmpty(),
    builderName,
    multibindings,
    build
)
