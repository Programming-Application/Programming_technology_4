package com.theater.arch;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * DDD レイヤ依存ルールを CI で機械検出する。
 *
 * <p>違反は {@code docs/architecture.md} §2 / §3 に違反していることを意味する。
 *
 * <p>"Bootstrap" レイヤ (= {@code com.theater.App}) のみが {@code Infrastructure} を直接参照できる (composition
 * root として各 BC Module を install する責務)。それ以外の層は infrastructure 越境禁止。
 */
@AnalyzeClasses(packages = "com.theater", importOptions = ImportOption.DoNotIncludeTests.class)
public class LayerArchTest {

  @ArchTest
  static final ArchRule LAYERED_DEPENDENCIES =
      layeredArchitecture()
          .consideringAllDependencies()
          .layer("Bootstrap")
          .definedBy("com.theater")
          .layer("UI")
          .definedBy("..ui..")
          .layer("Application")
          .definedBy("..application..")
          .layer("Domain")
          .definedBy("..domain..")
          .layer("Infrastructure")
          .definedBy("..infrastructure..")
          .whereLayer("UI")
          .mayOnlyBeAccessedByLayers("Bootstrap")
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("UI", "Bootstrap")
          .whereLayer("Infrastructure")
          .mayOnlyBeAccessedByLayers("Bootstrap")
          // 雛形段階 (実装が乗るまで) は空レイヤを許容
          .withOptionalLayers(true);
}
