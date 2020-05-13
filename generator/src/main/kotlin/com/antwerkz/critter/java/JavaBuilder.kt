package com.antwerkz.critter.java

import com.antwerkz.critter.Critter.addMethods
import com.antwerkz.critter.CritterContext
import com.antwerkz.critter.CritterField
import com.antwerkz.critter.FilterSieve
import com.antwerkz.critter.UpdateSieve
import com.antwerkz.critter.kotlin.KotlinBuilder
import com.antwerkz.critter.kotlin.KotlinBuilder.Companion
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeName
import dev.morphia.annotations.Reference
import org.jboss.forge.roaster.Roaster
import org.jboss.forge.roaster.model.source.JavaClassSource
import java.io.File
import java.io.PrintWriter

@ExperimentalStdlibApi
class JavaBuilder(private val context: CritterContext) {
    private var nested = mutableListOf<JavaClassSource>()

    fun build(directory: File) {
        context.classes.values.forEach { source ->
            nested.clear()
            val criteriaClass = Roaster.create(JavaClassSource::class.java)
                    .setPackage(source.pkgName + ".criteria")
                    .setName(source.name + "Criteria")
                    .setFinal(true)

            val filters = File(directory, criteriaClass.qualifiedName.replace('.', '/') + ".java")
            if (!source.isAbstract() && context.shouldGenerate(source.lastModified(), filters.lastModified())) {
                criteriaClass.addField("private static final ${criteriaClass.name}Impl instance = new ${criteriaClass.name}Impl()")
                criteriaClass.addMethod("""
                    private static ${criteriaClass.name}Impl ${source.name.toMethodCase()}() {
                        return instance;
                    }
                """.trimIndent())
                val impl = Roaster.create(JavaClassSource::class.java)
                        .setName(source.name + "CriteriaImpl")
                        .setStatic(true)
                        .setFinal(true)
                        .apply {
                            addField("private final String prefix")
                            addMethods("""
                                ${name}() {
                                    this.prefix = null;
                                }
            
                                ${name}(String fieldName) {
                                    this.prefix = fieldName;
                                }"""
                            ).forEach { it.isConstructor = true }
                        }

                criteriaClass.addMethod("""
                    private static String extendPath(String prefix, String path) {
                        return prefix != null ? prefix + "." + path : path;
                    }
                """)
                processFields(source, criteriaClass, impl)
                criteriaClass.addNestedType(impl)

                nested.forEach { type ->
                    criteriaClass.addNestedType(type)
                    type.imports.forEach { criteriaClass.addImport(it)}
                }

                generate(filters, criteriaClass)
            }
        }
    }

    private fun processFields(source: JavaClass, criteriaClass: JavaClassSource, impl: JavaClassSource) {
        source.fields.forEach { field ->
            criteriaClass.addField("public static final String ${field.name} = ${field.mappedName()}; ")
            addField(criteriaClass, impl, field)
        }
    }

    private fun addField(criteriaClass: JavaClassSource, impl: JavaClassSource, field: CritterField) {
        if (field.hasAnnotation(Reference::class.java)) {
            addReferenceCriteria(criteriaClass, impl, field)
        } else {
            impl.addFieldCriteriaMethod(criteriaClass, field)
            if (!field.isMappedType()) {
                addFieldCriteriaClass(field)
            }
        }
    }

    private fun addFieldCriteriaClass(field: CritterField) {
        addNestedType(Roaster.create(JavaClassSource::class.java))
                .apply {
                    name = "${field.name.toTitleCase()}FieldCriteria"
                    isStatic = true
                    isFinal = true
                    addField("private String prefix")

                    addMethod("""${name}(String prefix) {
                        |this.prefix = prefix;
                        |}""".trimMargin()).isConstructor = true
                    attachFilters(field)
                    attachUpdates(field)
                    addMethod("""
                        public String path() {
                            return extendPath(prefix, "${field.name}");
                        }""")
                }
    }

    private fun addReferenceCriteria(criteriaClass: JavaClassSource, impl: JavaClassSource, field: CritterField) {
        val fieldCriteriaName = field.name.toTitleCase() + "FieldCriteria"
        criteriaClass.addImport(fieldCriteriaName)
        criteriaClass.addMethod("""
            public static ${fieldCriteriaName} ${field.name}() {
                return instance.${field.name}();
            }""")
        impl.addMethod("""
            public ${fieldCriteriaName} ${field.name}() {
                return new ${fieldCriteriaName}(prefix);
            }""")
        addFieldCriteriaClass(field)
    }

    private fun addNestedType(nestedClass: JavaClassSource): JavaClassSource {
        nested.add(nestedClass)
        return nestedClass
    }

    private fun generate(outputFile: File, criteriaClass: JavaClassSource) {
        outputFile.parentFile.mkdirs()
        PrintWriter(outputFile).use { writer -> writer.println(criteriaClass.toString()) }
    }

    fun CritterField.mappedType(): JavaClass? {
        return context.classes[concreteType()]
    }

    fun CritterField.isMappedType(): Boolean {
        return mappedType() != null
    }

    private fun JavaClassSource.addFieldCriteriaMethod(criteriaClass: JavaClassSource, field: CritterField) {
        val concreteType = field.concreteType()
        val annotations = context.classes[concreteType]?.annotations
        val fieldCriteriaName = if (annotations == null) {
            field.name.toTitleCase() + "FieldCriteria"
        } else {
            val outer = concreteType.substringAfterLast('.') + "Criteria"
            val impl = concreteType.substringAfterLast('.') + "CriteriaImpl"

            criteriaClass.addImport("${criteriaClass.`package`}.${outer}.${impl}")
            impl
        }
        criteriaClass.addMethods("""
            public static ${fieldCriteriaName} ${field.name}() {
                return instance.${field.name}();
            }""".trimIndent())

        var prefix = "prefix"
        if(field.isMappedType()) {
            prefix = """extendPath(prefix, "${field.name}")"""
        }
        addMethods("""
            public ${fieldCriteriaName} ${field.name}() {
                return new ${fieldCriteriaName}(${prefix});
            }""".trimIndent())

    }

}

fun CritterField.concreteType() = if(fullParameterTypes.isNotEmpty()) fullParameterTypes.last() else type

@ExperimentalStdlibApi
private fun JavaClassSource.attachFilters(field: CritterField) {
    FilterSieve.handlers(field, this)
}

@ExperimentalStdlibApi
private fun JavaClassSource.attachUpdates(field: CritterField) {
    UpdateSieve.handlers(field, this)
}

fun String.toTitleCase(): String {
    return substring(0, 1).toUpperCase() + substring(1)
}

fun String.toMethodCase(): String {
    return substring(0, 1).toLowerCase() + substring(1)
}