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
 * @author Record Robotics
 */
public class UseExplicitTypes extends AJavaparserNodeMutator {
	private static final Logger LOGGER = LoggerFactory.getLogger(UseExplicitTypes.class);

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
			// Use fully-qualified description to build a concrete Type
			explicitType = StaticJavaParser.parseType(optResolvedType.get().describe());
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
	}

	private boolean canReplaceVarWithExplicit(NodeAndSymbolSolver<?> context, Expression initializer) {
		if (initializer == null) {
			// `var` requires an initializer; if none, nothing to do
			return false;
		} else if (initializer.isLambdaExpr()) {
			// var = lambda is illegal (target typing required), but guard anyway
			return false;
		} else if (initializer.isMethodReferenceExpr()) {
			// Same rationale as lambda; keep safe
			return false;
		}

		try {
			Optional<ResolvedType> optType = MethodCallExprHelpers.optResolvedType(context.editNode(initializer));
			if (optType.isEmpty()) {
				return false;
			}

			// Check we can render a proper explicit Type
			StaticJavaParser.parseType(optType.get().describe());
			return true;
		} catch (UnsolvedSymbolException | IllegalArgumentException e) {
			LOGGER.debug("Type could not be resolved or parsed for explicit replacement: {}", e.getMessage());
			return false;
		} catch (Exception e) {
			LOGGER.debug("Unexpected error while checking explicit replacement", e);
			return false;
		}
	}
}
