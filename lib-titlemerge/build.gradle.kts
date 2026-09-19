// NOTE: plugins are applied WITHOUT versions: the kotlin-gradle-plugin
// classes are already on the build classpath (root project), so requesting a
// version fails resolution ("already on the classpath with an unknown version").
// Type-safe accessors aren't generated for apply(), so use add() here.
apply(plugin = "java")
apply(plugin = "org.jetbrains.kotlin.jvm")

dependencies {
    add("testImplementation", "junit:junit:4.13.2")
}
