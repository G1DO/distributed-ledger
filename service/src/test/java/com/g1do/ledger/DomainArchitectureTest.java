package com.g1do.ledger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class DomainArchitectureTest {

  private final JavaClasses classes = new ClassFileImporter().importPackages("com.g1do.ledger");

  @Test
  void domainHasNoSpringDependency() {
    noClasses()
        .that()
        .resideInAPackage("com.g1do.ledger.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "jakarta.inject..")
        .check(classes);
  }

  @Test
  void domainHasNoJpaDependency() {
    noClasses()
        .that()
        .resideInAPackage("com.g1do.ledger.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "jakarta.persistence..", "org.hibernate..", "org.springframework.data..")
        .check(classes);
  }
}
