import com.squareup.javapoet.*
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.common.KafkaFuture
import javax.lang.model.element.Modifier
import java.lang.reflect.*

version = "0.0.2-SNAPSHOT"
group = "com.dexecr"

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.apache.kafka:kafka-clients:${project.properties["kafkaClientVersion"]}")
        classpath("com.squareup:javapoet:${project.properties["javapoetVersion"]}")
    }
}


plugins {
    id("java-library")
    id("maven-publish")
    signing
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
        vendor.set(JvmVendorSpec.ADOPTOPENJDK)
    }
    withJavadocJar()
    withSourcesJar()
}

java.sourceSets["main"].java {
    srcDir("${buildDir}/generated/sources/client")
}

repositories {
    mavenCentral()
}

dependencies {
    "api"("org.apache.kafka:kafka-clients:${project.properties["kafkaClientVersion"]}")
    "api"("io.projectreactor:reactor-core:${project.properties["reactorVersion"]}")
}

tasks.register<GenerateRxKafkaClientTask>("codeGen")

tasks.withType<JavaCompile>  {
    dependsOn("codeGen")
}

publishing {
    repositories {
        maven {
            val snapshotUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots"
            val releaseUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2"
            credentials {
                username = "${project.properties["ossrhUsername"]}"
                password = "${project.properties["ossrhPassword"]}"
            }
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotUrl else releaseUrl)
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("Reactive kafka clients")
                description.set("KafkaClients for reactor")
                url.set("https://github.com/dexecr/rx-kafka-clients/")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("dexecr")
                        name.set("dexecr")
                        email.set("dexecrd@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/dexecr/rx-kafka-clients.git")
                    developerConnection.set("scm:git:ssh://git@github.com/dexecr/rx-kafka-clients.git")
                    url.set("https://github.com/dexecr/rx-kafka-clients")
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}

tasks.withType<Jar> {
    into("META-INF/maven/${project.group}/${project.name}") {
        from(tasks.getByName("generatePomFileForMavenJavaPublication"))
        rename(".*", "pom.xml")
    }
}

abstract class GenerateRxKafkaClientTask : DefaultTask() {

    companion object {

        private val rxOperations: Map<String, Set<String>> = mapOf(
            "RxAdminTopicOperations" to setOf("listTopics", "deleteTopics", "describeTopics", "createTopics"),
            "RxAdminClusterOperations" to setOf("describeCluster", "describeFeatures", "updateFeatures"),
            "RxAdminAclOperations" to setOf("describeAcls", "createAcls", "deleteAcls"),
            "RxAdminConfigOperations" to setOf("describeConfigs", "alterConfigs", "incrementalAlterConfigs"),
            "RxAdminLogDirsOperations" to setOf("alterReplicaLogDirs", "describeLogDirs", "describeReplicaLogDirs"),
            "RxAdminDelegationTokenOperations" to setOf(
                "createDelegationToken", "renewDelegationToken", "expireDelegationToken", "describeDelegationToken"
            ),
            "RxAdminConsumerGroupOperations" to setOf(
                "describeConsumerGroups", "listConsumerGroups", "listConsumerGroupOffsets", "deleteConsumerGroups",
                "deleteConsumerGroupOffsets", "alterConsumerGroupOffsets", "removeMembersFromConsumerGroup"
            ),
            "RxAdminPartitionOperations" to setOf(
                "electLeaders", "alterPartitionReassignments", "listPartitionReassignments", "listOffsets",
                "createPartitions"
            ),
        )
        private const val PACKAGE_NAME = "com.dexecr.kafka.clients.rx.admin"
        private val RX_ADMIN_CLIENT_CLS: ClassName = ClassName.get(PACKAGE_NAME, "RxAdmin")
        private val KAFKA_RX_UTILS_CLS: ClassName = ClassName.get(PACKAGE_NAME, "KafkaRxUtils")
        private val MONO_CLS: ClassName = ClassName.get("reactor.core.publisher", "Mono")
    }

    @TaskAction
    fun run() {
        val methodsClassMapping = rxOperations.flatMap { (cls, methods) -> methods.map { it to cls } }.toMap()
        val rxAdminOperationsInterfaceBuilder = TypeSpec.interfaceBuilder("RxAdminOperations").addModifiers(Modifier.PUBLIC)
        val abstractRxAdminOperationsBuilder = TypeSpec.classBuilder("GenericRxAdminOperations")
            .addModifiers(Modifier.ABSTRACT)
            .addField(FieldSpec.builder(Admin::class.java, "admin", Modifier.FINAL, Modifier.PROTECTED).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addParameter(ParameterSpec.builder(Admin::class.java, "admin").build())
                .addModifiers(Modifier.PROTECTED)
                .addCode("this.admin = admin;")
                .build()
            )

        Admin::class.java.declaredMethods.filter { methodsClassMapping.containsKey(it.name) }
            .groupBy { methodsClassMapping[it.name] }
            .onEach { (cls, methods) ->
                generateSrc(createOperationInterface(cls!!, methods))
                abstractRxAdminOperationsBuilder.addMethods(rxAdminClientMethodsImpl(methods))
                rxAdminOperationsInterfaceBuilder.addSuperinterface(ClassName.get(PACKAGE_NAME, cls))
            }
            .values.flatten().map { it.returnType }.distinct().forEach { generateSrc(createRxResultClass(it)) }
        val rxAdminOperationsInterface = rxAdminOperationsInterfaceBuilder.build()
        generateSrc(JavaFile.builder(PACKAGE_NAME, rxAdminOperationsInterface).build())
        abstractRxAdminOperationsBuilder.addSuperinterface(ClassName.get(PACKAGE_NAME, rxAdminOperationsInterface.name))
        generateSrc(JavaFile.builder("${PACKAGE_NAME}.internal", abstractRxAdminOperationsBuilder.build()).build())
    }

    private fun toMono(arguments: Array<Type>): TypeName {
        return ParameterizedTypeName.get(MONO_CLS, *arguments.map { ParameterizedTypeName.get(it) }.toTypedArray())
    }

    private fun generateSrc(file: JavaFile) {
        println("Generating ${file.packageName}.${file.typeSpec?.name}...")
        file.writeTo(File("${project.buildDir}/generated/sources/client"))
    }

    private fun transformResultReturnType(method: Method): TypeName {
        if (method.returnType == KafkaFuture::class.java) {
            return toMono((method.genericReturnType as ParameterizedType).actualTypeArguments)
        }
        if (method.genericReturnType is ParameterizedType) {
            val types = (method.genericReturnType as ParameterizedType).actualTypeArguments.map {
                if (it is ParameterizedType && it.rawType == KafkaFuture::class.java) {
                    toMono(it.actualTypeArguments)
                } else {
                    ParameterizedTypeName.get(it)
                }
            }.toTypedArray()
            return ParameterizedTypeName.get(ClassName.get(method.returnType), *types)
        }
        return ParameterizedTypeName.get(method.returnType)
    }

    private fun transformResultClassMethod(method: Method): MethodSpec {
        val params = method.parameters.map { ParameterSpec.builder(it.type, it.name).build() }.toList()
        val doc = "@see \$L#\$L(${ method.parameters.joinToString(", ") { it.type.simpleName } })"
        val body = "return \$T.transform(proxy.\$L(${params.joinToString(", ") { it.name }}));"
        return MethodSpec.methodBuilder(method.name)
            .addModifiers(Modifier.PUBLIC)
            .addParameters(params)
            .returns(transformResultReturnType(method))
            .addJavadoc(doc, method.declaringClass.simpleName, method.name)
            .addCode(body, KAFKA_RX_UTILS_CLS, method.name)
            .build()
    }

    private fun createRxResultClass(nonRxCls: Class<*>): JavaFile {
        val specBuilder = TypeSpec
            .classBuilder("Rx${nonRxCls.simpleName}")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addField(nonRxCls, "proxy", Modifier.FINAL, Modifier.PRIVATE)
            .addMethod(MethodSpec.constructorBuilder()
                .addParameter(ParameterSpec.builder(nonRxCls, "proxy").build())
                .addModifiers(Modifier.PUBLIC)
                .addCode("this.proxy = proxy;")
                .build()
            )
        nonRxCls.declaredMethods.filter { `java.lang.reflect`.Modifier.isPublic(it.modifiers) }
            .map { transformResultClassMethod(it) }.forEach { specBuilder.addMethod(it) }
        return JavaFile.builder(PACKAGE_NAME, specBuilder.build()).build()
    }

    private fun createOperationInterface(name: String, methods: List<Method>): JavaFile {
        val specBuilder = TypeSpec.interfaceBuilder(name).addModifiers(Modifier.PUBLIC)
        methods.forEach {
            val params = it.parameters.map { param -> ParameterSpec.builder(param.parameterizedType, param.name).build() }
            val doc = "@see \$L#\$L(${ it.parameters.joinToString(", ") { param -> param.type.simpleName } })"
            specBuilder.addMethod(MethodSpec.methodBuilder(it.name)
                .addParameters(params)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotations(it.annotations.map { annotation -> AnnotationSpec.get(annotation) })
                .returns(ClassName.get(PACKAGE_NAME, "Rx${it.returnType.simpleName}"))
                .addJavadoc(doc, it.declaringClass.name, it.name)
                .build()
            )
        }
        return JavaFile.builder(PACKAGE_NAME, specBuilder.build()).build()
    }

    private fun rxAdminClientMethodsImpl(methods: List<Method>): List<MethodSpec> {
        return methods.map {
            val params = it.parameters.map { param -> ParameterSpec.builder(param.parameterizedType, param.name).build() }
            val returnType = ClassName.get(PACKAGE_NAME, "Rx${it.returnType.simpleName}")
            MethodSpec.methodBuilder(it.name)
                .addParameters(params)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType)
                .addAnnotations(it.annotations.map { annotation -> AnnotationSpec.get(annotation) })
                .addAnnotation(Override::class.java)
                .addCode("return new \$T(admin.\$L(${ params.joinToString(", ") { param -> param.name }  }));", returnType, it.name)
                .build()
        }
    }
}
