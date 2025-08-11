/*
 * Copyright 2025 Benoit Lacelle - SOLVEN
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
package eu.solven.cleanthat.engine.java.refactorer.mutators;

import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;
import com.google.common.collect.ImmutableSet;

import eu.solven.cleanthat.engine.java.IJdkVersionConstants;
import eu.solven.cleanthat.engine.java.refactorer.AJavaparserNodeMutator;
import eu.solven.cleanthat.engine.java.refactorer.NodeAndSymbolSolver;
import eu.solven.cleanthat.engine.java.refactorer.helpers.MethodCallExprHelpers;

/**
 * Turns 'var i = 10;' into 'int i = 10;'.
 *
 * <p>
 * Opposite of LocalVariableTypeInference.
 * 
 * // Removing duplicate processNotRecursively method
 * 
 * // ThreadLocal to track context for import management
 * 
 * @author Record Robotics
 */
@SuppressWarnings("PMD.GodClass")
public class UseExplicitTypes extends AJavaparserNodeMutator {
	private static final Logger LOGGER = LoggerFactory.getLogger(UseExplicitTypes.class);

	private static final String JAVA_LANG_PACKAGE = "java.lang.";

	// ThreadLocal to track context for import management
	private static final ThreadLocal<NodeAndSymbolSolver<?>> CURRENT_CONTEXT = new ThreadLocal<>();

	@Override
	public String minimalJavaVersion() {
		// We operate on code that may contain `var`
		return IJdkVersionConstants.JDK_10;
	}

	@Override
	public Set<String> getTags() {
		return ImmutableSet.of("ImplicitToExplicit");
	}

	@Override
	public Optional<String> getSonarId() {
		return Optional.empty();
	}

	@Override
	public Set<String> getSeeUrls() {
		return Set.of("https://openjdk.org/jeps/286",
				"https://pmd.github.io/latest/pmd_rules_java_codestyle.html#useexplicittypes");
	}

	@Override
	public Optional<String> getJSparrowId() {
		return Optional.empty();
	}

	@Override
	public String jSparrowUrl() {
		return "https://pmd.github.io/latest/pmd_rules_java_codestyle.html#useexplicittypes";
	}

	@Override
	protected boolean processNotRecursively(NodeAndSymbolSolver<?> node) {
		CURRENT_CONTEXT.set(node);
		try {
			if (!(node.getNode() instanceof VariableDeclarationExpr)) {
				return false;
			}
			var variableDeclarationExpr = (VariableDeclarationExpr) node.getNode();

			// `var` can't be used with multiple declarators; skip multi-declarations anyway
			if (variableDeclarationExpr.getVariables().size() != 1) {
				return false;
			}

			var singleVariableDeclaration = variableDeclarationExpr.getVariable(0);

			// Only operate on `var`
			Type type = singleVariableDeclaration.getType();
			if (!type.isVarType()) {
				return false;
			}

			var initializer = singleVariableDeclaration.getInitializer().orElse(null);
			if (!canReplaceVarWithExplicit(node, initializer)) {
				return false;
			}

			Optional<ResolvedType> optResolvedType = MethodCallExprHelpers.optResolvedType(node.editNode(initializer));
			if (optResolvedType.isEmpty()) {
				return false;
			}

			Type explicitType;
			try {
				explicitType = buildExplicitType(optResolvedType.get());
			} catch (UnsolvedSymbolException | IllegalArgumentException e) {
				LOGGER.debug("Unable to parse explicit type from resolved type: {}", e.getMessage());
				return false;
			} catch (Exception e) {
				LOGGER.debug("Unexpected error building explicit type from resolved type", e);
				return false;
			}

			// Build the replacement VariableDeclarationExpr
			var newVariableDeclarator =
					new VariableDeclarator(explicitType, singleVariableDeclaration.getName(), initializer);

			var newVariableDeclarationExpr = new VariableDeclarationExpr(newVariableDeclarator);
			newVariableDeclarationExpr.setModifiers(variableDeclarationExpr.getModifiers());
			newVariableDeclarationExpr.setAnnotations(variableDeclarationExpr.getAnnotations());

			return tryReplace(variableDeclarationExpr, newVariableDeclarationExpr);
		} finally {
			CURRENT_CONTEXT.remove();
		}
	}

	private boolean canReplaceVarWithExplicit(NodeAndSymbolSolver<?> context, Expression initializer) {
		if (initializer == null) {
			return false;
		} else if (initializer.isLambdaExpr() || initializer.isMethodReferenceExpr()) {
			return false;
		}

		// Skip anonymous classes with diamond operator as type resolution may be
		// incomplete
		if (initializer.isObjectCreationExpr()) {
			var objCreation = initializer.asObjectCreationExpr();
			if (objCreation.getAnonymousClassBody().isPresent() && objCreation.getType().isClassOrInterfaceType()
					&& objCreation.getType().asClassOrInterfaceType().getTypeArguments().isPresent()
					&& objCreation.getType().asClassOrInterfaceType().getTypeArguments().get().isEmpty()) {
				// This is an anonymous class with diamond operator <> - skip for now
				return false;
			}
		}

		try {
			Optional<ResolvedType> optType = MethodCallExprHelpers.optResolvedType(context.editNode(initializer));
			if (optType.isEmpty()) {
				return false;
			}
			buildExplicitType(optType.get());
			return true;
		} catch (Exception e) {
			LOGGER.debug("Type could not be resolved or parsed for explicit replacement: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Builds a JavaParser Type from a ResolvedType, handling generics, arrays, and library types.
	 */
	@SuppressWarnings("PMD.CognitiveComplexity")
	private Type buildExplicitType(ResolvedType resolvedType) {
		if (resolvedType.isArray()) {
			Type componentType = buildExplicitType(resolvedType.asArrayType().getComponentType());
			return StaticJavaParser.parseType(componentType + "[]");
		} else if (resolvedType.isPrimitive()) {
			return StaticJavaParser.parseType(resolvedType.describe());
		} else if (resolvedType.isReferenceType()) {
			var refType = resolvedType.asReferenceType();
			String qualifiedName = refType.getQualifiedName();
			String shortName = getShortName(qualifiedName);

			// Check for import and conflicts
			boolean canUseShortName = canUseShortName(shortName, qualifiedName);
			if (canUseShortName) {
				addImportIfNeeded(qualifiedName);
			}

			StringBuilder sb = new StringBuilder();
			if (canUseShortName) {
				// Handle nested types specially
				if (qualifiedName.contains(".") && !qualifiedName.startsWith(JAVA_LANG_PACKAGE)) {
					int lastDot = qualifiedName.lastIndexOf('.');
					String outerClass = qualifiedName.substring(0, lastDot);
					String nestedClass = qualifiedName.substring(lastDot + 1);

					// Check if we have import for the outer class
					var imports = getCurrentContext().getImports();
					boolean hasOuterImport = false;
					for (var imp : imports) {
						if (!imp.isAsterisk() && imp.getNameAsString().equals(outerClass)) {
							hasOuterImport = true;
							break;
						}
					}

					if (hasOuterImport) {
						sb.append(getShortName(outerClass)).append('.').append(nestedClass);
					} else {
						sb.append(shortName);
					}
				} else {
					sb.append(shortName);
				}
			} else {
				sb.append(qualifiedName);
			}

			if (!refType.getTypeParametersMap().isEmpty()) {
				sb.append('<');
				for (int i = 0; i < refType.getTypeParametersMap().size(); i++) {
					var tp = refType.getTypeParametersMap().get(i);
					sb.append(buildExplicitType(tp.b));
					if (i < refType.getTypeParametersMap().size() - 1) {
						sb.append(", ");
					}
				}
				sb.append('>');
			}
			return StaticJavaParser.parseType(sb.toString());
		} else if (resolvedType.isWildcard()) {
			return StaticJavaParser
					.parseType("? extends " + buildExplicitType(resolvedType.asWildcard().getBoundedType()));
		} else if (resolvedType.isTypeVariable()) {
			return StaticJavaParser.parseType(resolvedType.asTypeVariable().qualifiedName());
		} else {
			// Fallback to description
			return StaticJavaParser.parseType(resolvedType.describe());
		}
	}

	private boolean canUseShortName(String shortName, String qualifiedName) {
		// Check if shortName is already imported or in scope, and not conflicting
		// 1. Already imported (direct or wildcard)
		// 2. Not conflicting with another type in the same scope
		NodeAndSymbolSolver<?> context = getCurrentContext();
		if (context == null) {
			return false;
		}

		// For nested types like Map.Entry, prefer using the outer class if it's
		// imported
		if (qualifiedName.contains("$")) {
			// This is likely a nested type - be more conservative
			return false;
		}

		// Handle nested types like Map.Entry properly
		if (qualifiedName.contains(".") && !qualifiedName.startsWith(JAVA_LANG_PACKAGE)) {
			int lastDot = qualifiedName.lastIndexOf('.');
			String outerClass = qualifiedName.substring(0, lastDot);

			// Check if we have import for the outer class
			var imports = context.getImports();
			for (var imp : imports) {
				if (!imp.isAsterisk() && imp.getNameAsString().equals(outerClass)) {
					// We have an import for the outer class, use short form
					return true;
				}
			}
		}

		// Always allow short names for java.lang types
		if (qualifiedName.startsWith(JAVA_LANG_PACKAGE)) {
			return true;
		}

		var imports = context.getImports();
		boolean imported = false;
		for (var imp : imports) {
			if (imp.isAsterisk()) {
				if (qualifiedName.startsWith(imp.getNameAsString() + ".")) {
					imported = true;
					break;
				}
			} else if (imp.getNameAsString().equals(qualifiedName)) {
				imported = true;
				break;
			} else if (imp.getName().getIdentifier().equals(shortName)) {
				imported = true;
				break;
			}
		}

		return imported || !isShortNameConflicting(shortName, qualifiedName);
	}

	@SuppressWarnings("PMD.AvoidDuplicateLiterals")
	private boolean isShortNameConflicting(String shortName, String qualifiedName) {
		// Check for conflicts: if another import (direct or wildcard) brings in a type
		// with the same short name
		// Always allow short names for java.lang types
		if (qualifiedName.startsWith(JAVA_LANG_PACKAGE)) {
			return false;
		}

		NodeAndSymbolSolver<?> context = getCurrentContext();
		if (context == null) {
			return false;
		}
		var imports = context.getImports();

		for (var imp : imports) {
			if (imp.isAsterisk()) {
				// Wildcard import: could bring in any type from the package
				// If not java.lang, assume possible conflict
				String pkg = imp.getNameAsString();
				if (!"java.lang".equals(pkg) && !qualifiedName.startsWith(pkg + ".")) {
					// If another package could bring in the same short name, possible conflict
					// But we need to be more lenient here - only consider it a conflict if we know
					// there's actually a
					// type
					// For now, let's be more permissive
					continue;
				}
			} else {
				String impShort = getShortName(imp.getNameAsString());
				if (impShort.equals(shortName) && !imp.getNameAsString().equals(qualifiedName)) {
					return true;
				}
			}
		}

		// Also check java.lang (implicitly imported)
		if (!qualifiedName.startsWith(JAVA_LANG_PACKAGE)) {
			try {
				Class<?> langClass = Class.forName(JAVA_LANG_PACKAGE + shortName);
				if (langClass != null) {
					return true;
				}
			} catch (ClassNotFoundException e) {
				LOGGER.debug("No java.lang.{} class found", shortName, e);
			}
		}
		return false;
	}

	private void addImportIfNeeded(String qualifiedName) {
		NodeAndSymbolSolver<?> context = getCurrentContext();
		if (context == null) {
			return;
		}
		var imports = context.getImports();
		for (var imp : imports) {
			if (imp.isAsterisk()) {
				if (qualifiedName.startsWith(imp.getNameAsString() + ".")) {
					return;
				}
			} else if (imp.getNameAsString().equals(qualifiedName)) {
				return;
			} else if (imp.getName().getIdentifier().equals(getShortName(qualifiedName))) {
				return;
			}
		}
		context.addImport(qualifiedName, false, false);
	}

	private String getShortName(String qualifiedName) {
		int lastDot = qualifiedName.lastIndexOf('.');
		if (lastDot >= 0) {
			return qualifiedName.substring(lastDot + 1);
		} else {
			return qualifiedName;
		}
	}

	private NodeAndSymbolSolver<?> getCurrentContext() {
		return CURRENT_CONTEXT.get();
	}

}
