# NewPipe Extractor (rules from NewPipe's own app/proguard-rules.pro, trimmed to the extractor)
# timeago patterns are loaded by class name
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
# Rhino runs YouTube's player JS (signature / n-param) and generates classes at runtime
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
# protobuf-lite reads message fields by reflection (YouTube request/response protos)
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
