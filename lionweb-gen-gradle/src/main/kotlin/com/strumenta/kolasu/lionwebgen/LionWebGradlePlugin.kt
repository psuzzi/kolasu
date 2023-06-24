package com.strumenta.kolasu.lionwebgen

import ASTGenerator
import ASTGeneratorCommand
import com.strumenta.kolasu.lionweb.StarLasuLWLanguage
import io.lionweb.lioncore.java.language.Language
import io.lionweb.lioncore.java.serialization.JsonSerialization
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.io.FileInputStream

class LionWebGradlePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val configuration = extensions.create("lionweb", LionWebGradleExtension::class.java)
        configuration.outdir.convention(File(project.buildDir, "lionweb-gen"))
        val lionwebgen = target.tasks.create("lionwebgen") {
            it.doLast {
                println("LIonWeb generation task - started")
                println("  languages: ${configuration.languages.get()}")
                configuration.languages.get().forEach { languageFile ->
                    val jsonser = JsonSerialization.getStandardSerialization()
                    jsonser.nodeResolver.addTree(StarLasuLWLanguage)
                    val language = jsonser.unserializeToNodes(FileInputStream(languageFile)).first() as Language
                    val ktFiles = ASTGenerator(configuration.packageName.get(), language).generateClasses()
                    ktFiles.forEach { ktFile ->
                        val file = File(configuration.outdir.get(), ktFile.path)
                        file.parentFile.mkdirs()
                        file.writeText(ktFile.code)
                        println("  generated ${file.path}")
                    }
                }
                println("LIonWeb generation task - completed")
            }
        }
        lionwebgen.group = "lionweb"



    }

}