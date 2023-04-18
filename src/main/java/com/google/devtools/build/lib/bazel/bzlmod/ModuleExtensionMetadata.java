// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.syntax.Location;

/** The Starlark object passed to the implementation function of module extension metadata. */
@StarlarkBuiltin(
    name = "extension_metadata",
    category = DocCategory.BUILTIN,
    doc =
        "Return values of this type from a module extension's implementation function to "
            + "provide metadata about the repositories generated by the extension to Bazel.")
public class ModuleExtensionMetadata implements StarlarkValue {
  @Nullable private final ImmutableSet<String> explicitRootModuleDirectDeps;
  @Nullable private final ImmutableSet<String> explicitRootModuleDirectDevDeps;
  private final UseAllRepos useAllRepos;

  private ModuleExtensionMetadata(
      @Nullable Set<String> explicitRootModuleDirectDeps,
      @Nullable Set<String> explicitRootModuleDirectDevDeps,
      UseAllRepos useAllRepos) {
    this.explicitRootModuleDirectDeps =
        explicitRootModuleDirectDeps != null
            ? ImmutableSet.copyOf(explicitRootModuleDirectDeps)
            : null;
    this.explicitRootModuleDirectDevDeps =
        explicitRootModuleDirectDevDeps != null
            ? ImmutableSet.copyOf(explicitRootModuleDirectDevDeps)
            : null;
    this.useAllRepos = useAllRepos;
  }

  static ModuleExtensionMetadata create(
      Object rootModuleDirectDepsUnchecked, Object rootModuleDirectDevDepsUnchecked)
      throws EvalException {
    if (rootModuleDirectDepsUnchecked == Starlark.NONE
        && rootModuleDirectDevDepsUnchecked == Starlark.NONE) {
      return new ModuleExtensionMetadata(null, null, UseAllRepos.NO);
    }

    // When root_module_direct_deps = "all", accept both root_module_direct_dev_deps = None and
    // root_module_direct_dev_deps = [], but not root_module_direct_dev_deps = ["some_repo"].
    if (rootModuleDirectDepsUnchecked.equals("all")
        && rootModuleDirectDevDepsUnchecked.equals(StarlarkList.immutableOf())) {
      return new ModuleExtensionMetadata(null, null, UseAllRepos.REGULAR);
    }

    if (rootModuleDirectDevDepsUnchecked.equals("all")
        && rootModuleDirectDepsUnchecked.equals(StarlarkList.immutableOf())) {
      return new ModuleExtensionMetadata(null, null, UseAllRepos.DEV);
    }

    if (rootModuleDirectDepsUnchecked.equals("all")
        || rootModuleDirectDevDepsUnchecked.equals("all")) {
      throw Starlark.errorf(
          "if one of root_module_direct_deps and root_module_direct_dev_deps is "
              + "\"all\", the other must be an empty list");
    }

    if (rootModuleDirectDepsUnchecked instanceof String
        || rootModuleDirectDevDepsUnchecked instanceof String) {
      throw Starlark.errorf(
          "root_module_direct_deps and root_module_direct_dev_deps must be "
              + "None, \"all\", or a list of strings");
    }
    if ((rootModuleDirectDepsUnchecked == Starlark.NONE)
        != (rootModuleDirectDevDepsUnchecked == Starlark.NONE)) {
      throw Starlark.errorf(
          "root_module_direct_deps and root_module_direct_dev_deps must both be "
              + "specified or both be unspecified");
    }

    Sequence<String> rootModuleDirectDeps =
        Sequence.cast(rootModuleDirectDepsUnchecked, String.class, "root_module_direct_deps");
    Sequence<String> rootModuleDirectDevDeps =
        Sequence.cast(
            rootModuleDirectDevDepsUnchecked, String.class, "root_module_direct_dev_deps");

    Set<String> explicitRootModuleDirectDeps = new LinkedHashSet<>();
    for (String dep : rootModuleDirectDeps) {
      try {
        RepositoryName.validateUserProvidedRepoName(dep);
      } catch (EvalException e) {
        throw Starlark.errorf("in root_module_direct_deps: %s", e.getMessage());
      }
      if (!explicitRootModuleDirectDeps.add(dep)) {
        throw Starlark.errorf("in root_module_direct_deps: duplicate entry '%s'", dep);
      }
    }

    Set<String> explicitRootModuleDirectDevDeps = new LinkedHashSet<>();
    for (String dep : rootModuleDirectDevDeps) {
      try {
        RepositoryName.validateUserProvidedRepoName(dep);
      } catch (EvalException e) {
        throw Starlark.errorf("in root_module_direct_dev_deps: %s", e.getMessage());
      }
      if (explicitRootModuleDirectDeps.contains(dep)) {
        throw Starlark.errorf(
            "in root_module_direct_dev_deps: entry '%s' is also in " + "root_module_direct_deps",
            dep);
      }
      if (!explicitRootModuleDirectDevDeps.add(dep)) {
        throw Starlark.errorf("in root_module_direct_dev_deps: duplicate entry '%s'", dep);
      }
    }

    return new ModuleExtensionMetadata(
        explicitRootModuleDirectDeps, explicitRootModuleDirectDevDeps, UseAllRepos.NO);
  }

  public void evaluate(
      Collection<ModuleExtensionUsage> usages, Set<String> allRepos, EventHandler handler)
      throws EvalException {
    generateFixupMessage(usages, allRepos).ifPresent(handler::handle);
  }

  Optional<Event> generateFixupMessage(
      Collection<ModuleExtensionUsage> usages, Set<String> allRepos) throws EvalException {
    var rootUsages =
        usages.stream()
            .filter(usage -> usage.getUsingModule().equals(ModuleKey.ROOT))
            .collect(toImmutableList());
    if (rootUsages.isEmpty()) {
      // The root module doesn't use the current extension. Do not suggest fixes as the user isn't
      // expected to modify any other module's MODULE.bazel file.
      return Optional.empty();
    }

    var rootModuleDirectDevDeps = getRootModuleDirectDevDeps(allRepos);
    var rootModuleDirectDeps = getRootModuleDirectDeps(allRepos);
    if (rootModuleDirectDevDeps.isEmpty() && rootModuleDirectDeps.isEmpty()) {
      return Optional.empty();
    }

    Preconditions.checkState(
        rootModuleDirectDevDeps.isPresent() && rootModuleDirectDeps.isPresent());
    return generateFixupMessage(
        rootUsages, allRepos, rootModuleDirectDeps.get(), rootModuleDirectDevDeps.get());
  }

  private static Optional<Event> generateFixupMessage(
      List<ModuleExtensionUsage> rootUsages,
      Set<String> allRepos,
      Set<String> expectedImports,
      Set<String> expectedDevImports) {
    var actualDevImports =
        rootUsages.stream()
            .flatMap(usage -> usage.getDevImports().stream())
            .collect(toImmutableSet());
    var actualImports =
        rootUsages.stream()
            .flatMap(usage -> usage.getImports().keySet().stream())
            .filter(repo -> !actualDevImports.contains(repo))
            .collect(toImmutableSet());

    // All label strings that map to the same Label are equivalent for buildozer as it implements
    // the same normalization of label strings with no or empty repo name.
    ModuleExtensionUsage firstUsage = rootUsages.get(0);
    String extensionBzlFile = firstUsage.getExtensionBzlFile();
    String extensionName = firstUsage.getExtensionName();
    Location location = firstUsage.getLocation();

    var importsToAdd = ImmutableSortedSet.copyOf(Sets.difference(expectedImports, actualImports));
    var importsToRemove =
        ImmutableSortedSet.copyOf(Sets.difference(actualImports, expectedImports));
    var devImportsToAdd =
        ImmutableSortedSet.copyOf(Sets.difference(expectedDevImports, actualDevImports));
    var devImportsToRemove =
        ImmutableSortedSet.copyOf(Sets.difference(actualDevImports, expectedDevImports));

    if (importsToAdd.isEmpty()
        && importsToRemove.isEmpty()
        && devImportsToAdd.isEmpty()
        && devImportsToRemove.isEmpty()) {
      return Optional.empty();
    }

    var message =
        String.format(
            "The module extension %s defined in %s reported incorrect imports "
                + "of repositories via use_repo():\n\n",
            extensionName, extensionBzlFile);

    var allActualImports = ImmutableSortedSet.copyOf(Sets.union(actualImports, actualDevImports));
    var allExpectedImports =
        ImmutableSortedSet.copyOf(Sets.union(expectedImports, expectedDevImports));

    var invalidImports = ImmutableSortedSet.copyOf(Sets.difference(allActualImports, allRepos));
    if (!invalidImports.isEmpty()) {
      message +=
          String.format(
              "Imported, but not created by the extension (will cause the build to fail):\n"
                  + "    %s\n\n",
              String.join(", ", invalidImports));
    }

    var missingImports =
        ImmutableSortedSet.copyOf(Sets.difference(allExpectedImports, allActualImports));
    if (!missingImports.isEmpty()) {
      message +=
          String.format(
              "Not imported, but reported as direct dependencies by the extension (may cause the"
                  + " build to fail):\n"
                  + "    %s\n\n",
              String.join(", ", missingImports));
    }

    var indirectDepImports =
        ImmutableSortedSet.copyOf(
            Sets.difference(Sets.intersection(allActualImports, allRepos), allExpectedImports));
    if (!indirectDepImports.isEmpty()) {
      message +=
          String.format(
              "Imported, but reported as indirect dependencies by the extension:\n    %s\n\n",
              String.join(", ", indirectDepImports));
    }

    var fixupCommands =
        Stream.of(
                makeUseRepoCommand(
                    "use_repo_add", false, importsToAdd, extensionBzlFile, extensionName),
                makeUseRepoCommand(
                    "use_repo_remove", false, importsToRemove, extensionBzlFile, extensionName),
                makeUseRepoCommand(
                    "use_repo_add", true, devImportsToAdd, extensionBzlFile, extensionName),
                makeUseRepoCommand(
                    "use_repo_remove", true, devImportsToRemove, extensionBzlFile, extensionName))
            .flatMap(Optional::stream);

    return Optional.of(
        Event.warn(
            location,
            message
                + String.format(
                    "%s ** You can use the following buildozer command(s) to fix these"
                        + " issues:%s\n\n"
                        + "%s",
                    "\033[35m\033[1m",
                    "\033[0m",
                    fixupCommands.collect(Collectors.joining("\n")))));
  }

  private static Optional<String> makeUseRepoCommand(
      String cmd,
      boolean devDependency,
      Collection<String> repos,
      String extensionBzlFile,
      String extensionName) {
    if (repos.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        String.format(
            "buildozer '%s%s %s %s %s' //MODULE.bazel:all",
            cmd,
            devDependency ? " dev" : "",
            extensionBzlFile,
            extensionName,
            String.join(" ", repos)));
  }

  private Optional<ImmutableSet<String>> getRootModuleDirectDeps(Set<String> allRepos)
      throws EvalException {
    switch (useAllRepos) {
      case NO:
        if (explicitRootModuleDirectDeps != null) {
          Set<String> invalidRepos = Sets.difference(explicitRootModuleDirectDeps, allRepos);
          if (!invalidRepos.isEmpty()) {
            throw Starlark.errorf(
                "root_module_direct_deps contained the following repositories "
                    + "not generated by the extension: %s",
                String.join(", ", invalidRepos));
          }
        }
        return Optional.ofNullable(explicitRootModuleDirectDeps);
      case REGULAR:
        return Optional.of(ImmutableSet.copyOf(allRepos));
      case DEV:
        return Optional.of(ImmutableSet.of());
    }
    throw new IllegalStateException("not reached");
  }

  private Optional<ImmutableSet<String>> getRootModuleDirectDevDeps(Set<String> allRepos)
      throws EvalException {
    switch (useAllRepos) {
      case NO:
        if (explicitRootModuleDirectDevDeps != null) {
          Set<String> invalidRepos = Sets.difference(explicitRootModuleDirectDevDeps, allRepos);
          if (!invalidRepos.isEmpty()) {
            throw Starlark.errorf(
                "root_module_direct_dev_deps contained the following "
                    + "repositories not generated by the extension: %s",
                String.join(", ", invalidRepos));
          }
        }
        return Optional.ofNullable(explicitRootModuleDirectDevDeps);
      case REGULAR:
        return Optional.of(ImmutableSet.of());
      case DEV:
        return Optional.of(ImmutableSet.copyOf(allRepos));
    }
    throw new IllegalStateException("not reached");
  }

  private enum UseAllRepos {
    NO,
    REGULAR,
    DEV,
  }
}
