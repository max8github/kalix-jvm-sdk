/*
 * Copyright 2024 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kalix.codegen.java

import _root_.java.nio.file.Path
import kalix.codegen.DescriptorSet
import kalix.codegen.File
import kalix.codegen.GeneratedFiles
import kalix.codegen.Log
import kalix.codegen.ModelBuilder
import kalix.codegen.PackageNaming

/**
 * Responsible for generating Java source from an entity model
 */
object SourceGenerator {
  import kalix.codegen.SourceGeneratorUtils._

  def generate(
      model: ModelBuilder.Model,
      sourceDirectory: Path,
      testSourceDirectory: Path,
      integrationTestSourceDirectory: Path,
      generatedSourceDirectory: Path,
      generatedTestSourceDirectory: Path,
      mainClass: String)(implicit log: Log): Iterable[Path] = {
    val files = generateFiles(model, mainClass)(log)

    files.write(
      generatedSourceDirectory,
      sourceDirectory,
      generatedTestSourceDirectory,
      testSourceDirectory,
      integrationTestSourceDirectory)
  }

  /**
   * Generate Java source from entities where the target source and test source directories have no existing source.
   * Note that we only generate tests for entities where we are successful in generating an entity. The user may not
   * want a test otherwise.
   *
   * Also generates a main source file if it does not already exist.
   *
   * Impure.
   *
   * @param model
   *   The model of entity metadata to generate source file
   * @param sourceDirectory
   *   A directory to generate source files in, which can also containing existing source.
   * @param testSourceDirectory
   *   A directory to generate test source files in, which can also containing existing source.
   * @param integrationTestSourceDirectory
   *   A directory to generate integration test source files in, which can also containing existing source.
   * @param mainClass
   *   A fully qualified classname to be used as the main class
   * @return
   *   A collection of paths addressing source files generated by this function
   */
  def generateFiles(model: ModelBuilder.Model, mainClass: String)(implicit log: Log): GeneratedFiles = {
    val (mainClassPackageName, mainClassName) = disassembleClassName(mainClass)

    val mainClassPackage = PackageNaming.noDescriptor(mainClassPackageName)
    val componentSources = ComponentsSourceGenerator.generate(mainClassPackage, model.services)
    val mainSources = MainSourceGenerator.generate(model, mainClassPackage, mainClassName)

    if (model.services.values.isEmpty) {
      throw new IllegalStateException(
        "Project does not contain any gRPC service descriptors annotated as Kalix components with 'option (kalix.codegen)'. " +
        "For details on declaring services see documentation: https://docs.kalix.io/java/writing-grpc-descriptors-protobuf.html#_service")
    }
    componentSources ++ mainSources ++ model.services.values
      .map {
        case service: ModelBuilder.EntityService =>
          val entity = model.lookupEntity(service)
          EntityServiceSourceGenerator
            .generate(entity, service, mainClassPackageName, mainClassName) ++
          (entity match {
            case ese: ModelBuilder.EventSourcedEntity => EventSourcedEntityTestKitGenerator.generate(ese, service)
            case ve: ModelBuilder.ValueEntity         => ValueEntityTestKitGenerator.generate(ve, service)
            case _: ModelBuilder.ReplicatedEntity     =>
              // FIXME implement for replicated entity
              GeneratedFiles.Empty
          })
        case service: ModelBuilder.ViewService => ViewServiceSourceGenerator.generate(service)
        case service: ModelBuilder.ActionService =>
          ActionServiceSourceGenerator.generate(service, mainClassPackageName) ++
            ActionTestKitGenerator.generate(service)
        case _ => GeneratedFiles.Empty
      }
      .reduce(_ ++ _)
  }

  def generate(
      protobufDescriptor: java.io.File,
      sourceDirectory: Path,
      testSourceDirectory: Path,
      integrationTestSourceDirectory: Path,
      generatedSourceDirectory: Path,
      generatedTestSourceDirectory: Path,
      mainClass: String)(implicit log: Log): Iterable[Path] = {
    val descriptors =
      DescriptorSet.fileDescriptors(protobufDescriptor) match {
        case Right(fileDescriptors) =>
          fileDescriptors match {
            case Right(files) => files
            case Left(failure) =>
              throw new RuntimeException(
                s"There was a problem building the file descriptor from its protobuf: $failure")
          }
        case Left(failure) =>
          throw new RuntimeException(s"There was a problem opening the protobuf descriptor file ${failure}", failure.e)
      }

    implicit val e = ProtoMessageTypeExtractor

    SourceGenerator.generate(
      ModelBuilder.introspectProtobufClasses(descriptors),
      sourceDirectory,
      testSourceDirectory,
      integrationTestSourceDirectory,
      generatedSourceDirectory,
      generatedTestSourceDirectory,
      mainClass)

  }

}
