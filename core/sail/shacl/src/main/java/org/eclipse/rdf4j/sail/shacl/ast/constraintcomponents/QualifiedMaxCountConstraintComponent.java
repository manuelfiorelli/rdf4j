/*******************************************************************************
 * Copyright (c) 2019 Eclipse RDF4J contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 *******************************************************************************/
package org.eclipse.rdf4j.sail.shacl.ast.constraintcomponents;

import static org.eclipse.rdf4j.model.util.Values.literal;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.SHACL;
import org.eclipse.rdf4j.sail.shacl.SourceConstraintComponent;
import org.eclipse.rdf4j.sail.shacl.ValidationSettings;
import org.eclipse.rdf4j.sail.shacl.ast.Cache;
import org.eclipse.rdf4j.sail.shacl.ast.NodeShape;
import org.eclipse.rdf4j.sail.shacl.ast.PropertyShape;
import org.eclipse.rdf4j.sail.shacl.ast.ShaclParsingException;
import org.eclipse.rdf4j.sail.shacl.ast.ShaclProperties;
import org.eclipse.rdf4j.sail.shacl.ast.ShaclUnsupportedException;
import org.eclipse.rdf4j.sail.shacl.ast.Shape;
import org.eclipse.rdf4j.sail.shacl.ast.StatementMatcher;
import org.eclipse.rdf4j.sail.shacl.ast.ValidationQuery;
import org.eclipse.rdf4j.sail.shacl.ast.paths.Path;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.AbstractBulkJoinPlanNode;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.BulkedExternalLeftOuterJoin;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.GroupByCountFilter;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.LeftOuterJoin;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.NotValuesIn;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.PlanNode;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.PlanNodeProvider;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.TrimToTarget;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.TupleMapper;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.UnionNode;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.Unique;
import org.eclipse.rdf4j.sail.shacl.ast.planNodes.ValidationTuple;
import org.eclipse.rdf4j.sail.shacl.ast.targets.EffectiveTarget;
import org.eclipse.rdf4j.sail.shacl.ast.targets.TargetChain;
import org.eclipse.rdf4j.sail.shacl.wrapper.data.ConnectionsGroup;
import org.eclipse.rdf4j.sail.shacl.wrapper.shape.ShapeSource;

public class QualifiedMaxCountConstraintComponent extends AbstractConstraintComponent {
	Shape qualifiedValueShape;
	boolean qualifiedValueShapesDisjoint;
	Long qualifiedMaxCount;

	public QualifiedMaxCountConstraintComponent(Resource id, ShapeSource shapeSource,
			Shape.ParseSettings parseSettings, Cache cache, Boolean qualifiedValueShapesDisjoint,
			Long qualifiedMaxCount) {
		super(id);

		ShaclProperties p = new ShaclProperties(id, shapeSource);

		this.qualifiedValueShapesDisjoint = qualifiedValueShapesDisjoint;
		this.qualifiedMaxCount = qualifiedMaxCount;

		if (p.getType() == SHACL.NODE_SHAPE) {
			qualifiedValueShape = NodeShape.getInstance(p, shapeSource, parseSettings, cache);
		} else if (p.getType() == SHACL.PROPERTY_SHAPE) {
			qualifiedValueShape = PropertyShape.getInstance(p, shapeSource, parseSettings, cache);
		} else {
			throw new IllegalStateException("Unknown shape type for " + p.getId());
		}

	}

	public QualifiedMaxCountConstraintComponent(QualifiedMaxCountConstraintComponent constraintComponent) {
		super(constraintComponent.getId());
		this.qualifiedValueShape = (Shape) constraintComponent.qualifiedValueShape.deepClone();
		this.qualifiedValueShapesDisjoint = constraintComponent.qualifiedValueShapesDisjoint;
		this.qualifiedMaxCount = constraintComponent.qualifiedMaxCount;
	}

	@Override
	public void toModel(Resource subject, IRI predicate, Model model, Set<Resource> cycleDetection) {
		model.add(subject, SHACL.QUALIFIED_VALUE_SHAPE, getId());

		if (qualifiedValueShapesDisjoint) {
			model.add(subject, SHACL.QUALIFIED_VALUE_SHAPES_DISJOINT, literal(qualifiedValueShapesDisjoint));
		}

		if (qualifiedMaxCount != null) {
			model.add(subject, SHACL.QUALIFIED_MAX_COUNT, literal(BigInteger.valueOf(qualifiedMaxCount)));
		}

		qualifiedValueShape.toModel(null, null, model, cycleDetection);

	}

	@Override
	public void setTargetChain(TargetChain targetChain) {
		super.setTargetChain(targetChain);
		qualifiedValueShape.setTargetChain(targetChain.setOptimizable(false));
	}

	@Override
	public SourceConstraintComponent getConstraintComponent() {
		return SourceConstraintComponent.QualifiedMaxCountConstraintComponent;
	}

	@Override
	public ValidationQuery generateSparqlValidationQuery(ConnectionsGroup connectionsGroup,
			ValidationSettings validationSettings,
			boolean negatePlan, boolean negateChildren, Scope scope) {
		assert scope == Scope.propertyShape;
		throw new ShaclUnsupportedException();
	}

	@Override
	public PlanNode generateTransactionalValidationPlan(ConnectionsGroup connectionsGroup,
			ValidationSettings validationSettings,
			PlanNodeProvider overrideTargetNode, Scope scope) {

		if (scope != Scope.propertyShape) {
			throw new ShaclParsingException(
					"QualifiedMaxCountConstraintComponent can only be used on property shapes!");
		}

		StatementMatcher.StableRandomVariableProvider stableRandomVariableProvider = new StatementMatcher.StableRandomVariableProvider();

		PlanNode target;

		EffectiveTarget effectiveTarget = getTargetChain().getEffectiveTarget(scope,
				connectionsGroup.getRdfsSubClassOfReasoner(), stableRandomVariableProvider);

		if (overrideTargetNode != null) {
			target = effectiveTarget
					.extend(overrideTargetNode.getPlanNode(), connectionsGroup, validationSettings.getDataGraph(),
							scope, EffectiveTarget.Extend.right,
							false, null);
		} else {
			target = getAllTargetsPlan(connectionsGroup, validationSettings.getDataGraph(), scope,
					stableRandomVariableProvider, validationSettings);
		}

		PlanNode planNode = negated(connectionsGroup, validationSettings, overrideTargetNode, scope);

		planNode = new LeftOuterJoin(target, planNode, connectionsGroup);

		GroupByCountFilter groupByCountFilter = new GroupByCountFilter(planNode, count -> count > qualifiedMaxCount,
				connectionsGroup);
		return Unique.getInstance(new TrimToTarget(groupByCountFilter, connectionsGroup), false, connectionsGroup);

	}

	public PlanNode negated(ConnectionsGroup connectionsGroup, ValidationSettings validationSettings,
			PlanNodeProvider overrideTargetNode, Scope scope) {

		StatementMatcher.StableRandomVariableProvider stableRandomVariableProvider = new StatementMatcher.StableRandomVariableProvider();

		PlanNodeProvider planNodeProvider = () -> {

			PlanNode target;

			if (overrideTargetNode == null) {
				target = getAllTargetsPlan(connectionsGroup, validationSettings.getDataGraph(), scope,
						stableRandomVariableProvider, validationSettings);
			} else {
				target = getTargetChain()
						.getEffectiveTarget(scope, connectionsGroup.getRdfsSubClassOfReasoner(),
								stableRandomVariableProvider)
						.extend(overrideTargetNode.getPlanNode(), connectionsGroup, validationSettings.getDataGraph(),
								scope, EffectiveTarget.Extend.right,
								false, null);
			}

			target = Unique.getInstance(new TrimToTarget(target, connectionsGroup), false, connectionsGroup);

			PlanNode relevantTargetsWithPath = new BulkedExternalLeftOuterJoin(
					target,
					connectionsGroup.getBaseConnection(),
					validationSettings.getDataGraph(), getTargetChain().getPath()
							.get()
							.getTargetQueryFragment(new StatementMatcher.Variable("a"),
									new StatementMatcher.Variable("c"),
									connectionsGroup.getRdfsSubClassOfReasoner(), stableRandomVariableProvider,
									Set.of()),
					(b) -> new ValidationTuple(b.getValue("a"), b.getValue("c"), scope, true,
							validationSettings.getDataGraph()),
					connectionsGroup, AbstractBulkJoinPlanNode.DEFAULT_VARS);

			return new TupleMapper(relevantTargetsWithPath, t -> {
				List<Value> targetChain = t.getTargetChain(true);
				return new ValidationTuple(targetChain, Scope.propertyShape, false, validationSettings.getDataGraph());
			}, connectionsGroup);

		};

		PlanNode planNode = qualifiedValueShape.generateTransactionalValidationPlan(
				connectionsGroup,
				validationSettings,
				planNodeProvider,
				scope
		);

		PlanNode invalid = Unique.getInstance(planNode, false, connectionsGroup);

		PlanNode allTargetsPlan;

		if (overrideTargetNode == null) {
			allTargetsPlan = getAllTargetsPlan(connectionsGroup, validationSettings.getDataGraph(), scope,
					stableRandomVariableProvider, validationSettings);
		} else {
			allTargetsPlan = getTargetChain()
					.getEffectiveTarget(scope, connectionsGroup.getRdfsSubClassOfReasoner(),
							stableRandomVariableProvider)
					.extend(overrideTargetNode.getPlanNode(), connectionsGroup, validationSettings.getDataGraph(),
							scope, EffectiveTarget.Extend.right,
							false, null);
		}

		allTargetsPlan = Unique.getInstance(new TrimToTarget(allTargetsPlan, connectionsGroup), false,
				connectionsGroup);

		allTargetsPlan = new BulkedExternalLeftOuterJoin(
				allTargetsPlan,
				connectionsGroup.getBaseConnection(),
				validationSettings.getDataGraph(), getTargetChain().getPath()
						.get()
						.getTargetQueryFragment(new StatementMatcher.Variable("a"), new StatementMatcher.Variable("c"),
								connectionsGroup.getRdfsSubClassOfReasoner(), stableRandomVariableProvider, Set.of()),
				(b) -> new ValidationTuple(b.getValue("a"), b.getValue("c"), scope, true,
						validationSettings.getDataGraph()),
				connectionsGroup, AbstractBulkJoinPlanNode.DEFAULT_VARS);

		invalid = new NotValuesIn(allTargetsPlan, invalid, connectionsGroup);

		return invalid;

	}

	@Override
	public PlanNode getAllTargetsPlan(ConnectionsGroup connectionsGroup, Resource[] dataGraph, Scope scope,
			StatementMatcher.StableRandomVariableProvider stableRandomVariableProvider,
			ValidationSettings validationSettings) {
		assert scope == Scope.propertyShape;

		EffectiveTarget effectiveTarget = getTargetChain()
				.getEffectiveTarget(Scope.propertyShape, connectionsGroup.getRdfsSubClassOfReasoner(),
						stableRandomVariableProvider);

		Path path = getTargetChain().getPath().orElseThrow();

		PlanNode allTargets = getAllTargetsIncludingThoseAddedByPath(connectionsGroup, validationSettings, scope,
				effectiveTarget, path, false);

		PlanNode subTargets = qualifiedValueShape.getAllTargetsPlan(connectionsGroup, dataGraph, scope,
				new StatementMatcher.StableRandomVariableProvider(), validationSettings);

		return Unique.getInstance(
				new TrimToTarget(
						UnionNode.getInstanceDedupe(connectionsGroup, allTargets, subTargets),
						connectionsGroup
				),
				false, connectionsGroup
		);

	}

	@Override
	public ConstraintComponent deepClone() {
		return new QualifiedMaxCountConstraintComponent(this);

	}

	@Override
	public boolean requiresEvaluation(ConnectionsGroup connectionsGroup, Scope scope, Resource[] dataGraph,
			StatementMatcher.StableRandomVariableProvider stableRandomVariableProvider) {
		return true;
	}

	@Override
	public List<Literal> getDefaultMessage() {
		return List.of();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		QualifiedMaxCountConstraintComponent that = (QualifiedMaxCountConstraintComponent) o;

		if (qualifiedValueShapesDisjoint != that.qualifiedValueShapesDisjoint) {
			return false;
		}
		if (!qualifiedValueShape.equals(that.qualifiedValueShape)) {
			return false;
		}
		return Objects.equals(qualifiedMaxCount, that.qualifiedMaxCount);
	}

	@Override
	public int hashCode() {
		int result = qualifiedValueShape.hashCode();
		result = 31 * result + (qualifiedValueShapesDisjoint ? 1 : 0);
		result = 31 * result + (qualifiedMaxCount != null ? qualifiedMaxCount.hashCode() : 0);
		return result + "QualifiedMaxCountConstraintComponent".hashCode();
	}
}
