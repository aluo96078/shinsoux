// R5-2 (docs/architecture/0006): build code that is not configuration lives here, compiled once.
plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("codegen") {
            id = "shinsou.codegen"
            implementationClass = "dev.shinsou.build.CodegenPlugin"
        }
    }
}
