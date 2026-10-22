plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// Модели запросов и ответов сервера — общие для сервера, десктопа и (позже) Android
kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serializationJson)
        }
    }
}
