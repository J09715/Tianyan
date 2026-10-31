# harness module consumer rules for R8 / proguard.
#
# Ktor server reflects / references classes that are absent from Android runtime.
# Tell R8 not to fail when referenced symbols are missing; Ktor's own ProGuard rules
# already cover the rest.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn org.fusesource.jansi.**
-dontwarn com.sun.nio.file.SensitivityWatchEventModifier
-dontwarn sun.nio.fs.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.apache.log4j.**
-dontwarn javax.script.**
-dontwarn org.codehaus.groovy.**
-dontwarn io.netty.**

# Keep Ktor runtime classes that R8 might aggressively strip when referenced via reflection
# (routing DSL, content negotiation, websocket pipeline).
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**

# Rhino's JavaToJSONConverters <clinit> references desktop-only java.beans APIs.
# CapabilityScriptRunner never calls NativeJSON.stringify or NativeJSON.parse: completion
# values, the sandbox JSON.stringify, the sandbox JSON.parse and capability.list/inspect all
# run through pure Kotlin code (jsToJsonString / SandboxJsonReader), so that class stays
# uninitialized on every supported path. R8 still sees the bytecode references. dontwarn does
# not make NativeJSON.stringify safe — the rejecting Java-to-JSON converter cannot stop the clinit.
# NativeJSON.parse is NOT an acceptable fallback either: on the v1.4.0 release build every call
# threw NullPointerException ("Attempt to invoke interface method … on a null object
# reference"), even for JSON.parse('123'), and the failure escaped script-level try/catch.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# Rhino resolves its evaluator implementation by *string name* (Kit.classOrNull →
# Class.forName), never through a static type reference:
#   Context.interpreterClass = classOrNull("org.mozilla.javascript.Interpreter")   // interpreted mode
# Release builds run R8 with proguard-android-optimize.txt (allowaccessmodification +
# repackage/obfuscate), so without a keep rule the class loses its real name and
# Class.forName() returns null. Context.createInterpreter() then calls
# Kit.newInstanceOrNull(null) → null.getDeclaredConstructor() → NullPointerException
# (Kit's catch list has no NPE), and because this happens while *compiling* the script,
# every script fails before its first statement runs — including `1 + 1`.
# Verified on the v1.3.0 release dex: no `Lorg/mozilla/javascript/*` descriptor survives
# while the literal "org.mozilla.javascript.Interpreter" is still passed to forName.
-keep class org.mozilla.javascript.Interpreter { *; }

# Do NOT keep org.mozilla.javascript.optimizer.Codegen: on Android codegenClass is forced
# to null (Rhino detects androidApi > 0 and never enables bytecode mode), and keeping it
# drags Nashorn's Bootstrapper into the graph → `Missing class jdk.dynalink.*` fails
# :app:minifyReleaseWithR8. Verified by release CI run 39412202648.
