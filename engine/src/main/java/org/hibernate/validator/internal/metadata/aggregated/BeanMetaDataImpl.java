/*
* JBoss, Home of Professional Open Source
* Copyright 2009, Red Hat, Inc. and/or its affiliates, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,  
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.hibernate.validator.internal.metadata.aggregated;

import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.validation.groups.Default;
import javax.validation.metadata.BeanDescriptor;
import javax.validation.metadata.ConstructorDescriptor;
import javax.validation.metadata.MethodDescriptor;
import javax.validation.metadata.PropertyDescriptor;

import org.hibernate.validator.internal.metadata.aggregated.ConstraintMetaData.ConstraintMetaDataKind;
import org.hibernate.validator.internal.metadata.core.ConstraintHelper;
import org.hibernate.validator.internal.metadata.core.MetaConstraint;
import org.hibernate.validator.internal.metadata.descriptor.BeanDescriptorImpl;
import org.hibernate.validator.internal.metadata.descriptor.ConstraintDescriptorImpl;
import org.hibernate.validator.internal.metadata.raw.BeanConfiguration;
import org.hibernate.validator.internal.metadata.raw.ConfigurationSource;
import org.hibernate.validator.internal.metadata.raw.ConstrainedElement;
import org.hibernate.validator.internal.metadata.raw.ConstrainedElement.ConstrainedElementKind;
import org.hibernate.validator.internal.metadata.raw.ConstrainedExecutable;
import org.hibernate.validator.internal.metadata.raw.ConstrainedField;
import org.hibernate.validator.internal.metadata.raw.ConstrainedType;
import org.hibernate.validator.internal.metadata.raw.ExecutableElement;
import org.hibernate.validator.internal.util.CollectionHelper.Partitioner;
import org.hibernate.validator.internal.util.ReflectionHelper;
import org.hibernate.validator.internal.util.logging.Log;
import org.hibernate.validator.internal.util.logging.LoggerFactory;
import org.hibernate.validator.spi.group.DefaultGroupSequenceProvider;

import static org.hibernate.validator.internal.util.CollectionHelper.newArrayList;
import static org.hibernate.validator.internal.util.CollectionHelper.newHashMap;
import static org.hibernate.validator.internal.util.CollectionHelper.newHashSet;
import static org.hibernate.validator.internal.util.CollectionHelper.partition;
import static org.hibernate.validator.internal.util.ReflectionHelper.computeAllImplementedInterfaces;

/**
 * This class encapsulates all meta data needed for validation. Implementations of {@code Validator} interface can
 * instantiate an instance of this class and delegate the metadata extraction to it.
 *
 * @author Hardy Ferentschik
 * @author Gunnar Morling
 * @author Kevin Pollet <kevin.pollet@serli.com> (C) 2011 SERLI
 */
public final class BeanMetaDataImpl<T> implements BeanMetaData<T> {

	private static final Log log = LoggerFactory.make();

	/**
	 * The root bean class for this meta data.
	 */
	private final Class<T> beanClass;

	/**
	 * Set of all constraints for this bean type (defined on any implemented interfaces or super types)
	 */
	private final Set<MetaConstraint<?>> allMetaConstraints;

	/**
	 * Set of all constraints which are directly defined on the bean or any of the directly implemented interfaces
	 */
	private final Set<MetaConstraint<?>> directMetaConstraints;

	/**
	 * Contains constrained related meta data for all methods and constructors
	 * of the type represented by this bean meta data. Keyed by executable,
	 * values are an aggregated view on each executable together with all the
	 * executables from the inheritance hierarchy with the same signature.
	 */
	private final Map<String, ExecutableMetaData> executableMetaData;

	/**
	 * Property meta data keyed against the property name
	 */
	private final Map<String, PropertyMetaData> propertyMetaData;

	/**
	 * The cascaded properties of this bean.
	 */
	private final Set<Cascadable> cascadedProperties;

	/**
	 * The bean descriptor for this bean.
	 */
	private final BeanDescriptor beanDescriptor;

	/**
	 * The default groups sequence for this bean class.
	 */
	private List<Class<?>> defaultGroupSequence = newArrayList();

	/**
	 * The default group sequence provider.
	 *
	 * @see org.hibernate.validator.group.GroupSequenceProvider
	 * @see DefaultGroupSequenceProvider
	 */
	private DefaultGroupSequenceProvider<? super T> defaultGroupSequenceProvider;

	/**
	 * The class hierarchy for this class starting with the class itself going up the inheritance chain. Interfaces
	 * are not included.
	 */
	private final List<Class<?>> classHierarchyWithoutInterfaces;

	/**
	 * Creates a new {@link BeanMetaDataImpl}
	 *
	 * @param beanClass The Java type represented by this meta data object.
	 * @param defaultGroupSequence The default group sequence.
	 * @param defaultGroupSequenceProvider The default group sequence provider if set.
	 * @param constraintMetaData All constraint meta data relating to the represented type.
	 */
	public BeanMetaDataImpl(Class<T> beanClass,
							List<Class<?>> defaultGroupSequence,
							DefaultGroupSequenceProvider<? super T> defaultGroupSequenceProvider,
							Set<ConstraintMetaData> constraintMetaData) {

		this.beanClass = beanClass;
		this.propertyMetaData = newHashMap();

		Set<PropertyMetaData> propertyMetaDataSet = newHashSet();
		Set<ExecutableMetaData> executableMetaDataSet = newHashSet();

		for ( ConstraintMetaData oneElement : constraintMetaData ) {
			if ( oneElement.getKind() == ConstraintMetaDataKind.PROPERTY ) {
				propertyMetaDataSet.add( (PropertyMetaData) oneElement );
			}
			else {
				executableMetaDataSet.add( (ExecutableMetaData) oneElement );
			}
		}

		Set<Cascadable> cascadedProperties = newHashSet();
		Set<MetaConstraint<?>> allMetaConstraints = newHashSet();

		for ( PropertyMetaData oneProperty : propertyMetaDataSet ) {

			propertyMetaData.put( oneProperty.getName(), oneProperty );

			if ( oneProperty.isCascading() ) {
				cascadedProperties.add( oneProperty );
			}

			allMetaConstraints.addAll( oneProperty.getConstraints() );
		}

		this.cascadedProperties = Collections.unmodifiableSet( cascadedProperties );
		this.allMetaConstraints = Collections.unmodifiableSet( allMetaConstraints );

		this.classHierarchyWithoutInterfaces = ReflectionHelper.computeClassHierarchy( beanClass, false );

		setDefaultGroupSequenceOrProvider( defaultGroupSequence, defaultGroupSequenceProvider );

		this.directMetaConstraints = buildDirectConstraintSets();

		this.executableMetaData = Collections.unmodifiableMap( byIdentifier( executableMetaDataSet ) );

		this.beanDescriptor = new BeanDescriptorImpl(
				beanClass,
				getClassLevelConstraintsAsDescriptors(),
				getConstrainedPropertiesAsDescriptors(),
				getConstrainedMethodsAsDescriptors(),
				getConstrainedConstructorsAsDescriptors(),
				defaultGroupSequenceIsRedefined(),
				getDefaultGroupSequence( null )
		);
	}

	@Override
	public Class<T> getBeanClass() {
		return beanClass;
	}

	@Override
	public BeanDescriptor getBeanDescriptor() {
		return beanDescriptor;
	}

	@Override
	public Set<Cascadable> getCascadables() {
		return cascadedProperties;
	}

	@Override
	public PropertyMetaData getMetaDataFor(String propertyName) {
		return propertyMetaData.get( propertyName );
	}

	@Override
	public Set<MetaConstraint<?>> getMetaConstraints() {
		return allMetaConstraints;
	}

	@Override
	public Set<MetaConstraint<?>> getDirectMetaConstraints() {
		return directMetaConstraints;
	}

	@Override
	public ExecutableMetaData getMetaDataFor(ExecutableElement executable) {
		return executableMetaData.get( executable.getIdentifier() );
	}

	@Override
	public List<Class<?>> getDefaultGroupSequence(T beanState) {
		if ( hasDefaultGroupSequenceProvider() ) {
			List<Class<?>> providerDefaultGroupSequence = defaultGroupSequenceProvider.getValidationGroups( beanState );
			return getValidDefaultGroupSequence( providerDefaultGroupSequence );
		}

		return Collections.unmodifiableList( defaultGroupSequence );
	}

	@Override
	public boolean defaultGroupSequenceIsRedefined() {
		return defaultGroupSequence.size() > 1 || hasDefaultGroupSequenceProvider();
	}

	@Override
	public List<Class<?>> getClassHierarchy() {
		return classHierarchyWithoutInterfaces;
	}

	private Set<ConstraintDescriptorImpl<?>> getClassLevelConstraintsAsDescriptors() {

		Set<MetaConstraint<?>> classLevelConstraints = getClassLevelConstraints( allMetaConstraints );

		Set<ConstraintDescriptorImpl<?>> theValue = newHashSet();

		for ( MetaConstraint<?> oneConstraint : classLevelConstraints ) {
			theValue.add( oneConstraint.getDescriptor() );
		}

		return theValue;
	}

	private Map<String, PropertyDescriptor> getConstrainedPropertiesAsDescriptors() {
		Map<String, PropertyDescriptor> theValue = newHashMap();

		for ( Entry<String, PropertyMetaData> entry : propertyMetaData.entrySet() ) {
			if ( entry.getValue().isConstrained() && entry.getValue().getName() != null ) {
				theValue.put(
						entry.getKey(),
						entry.getValue().asDescriptor(
								defaultGroupSequenceIsRedefined(),
								getDefaultGroupSequence( null )
						)
				);
			}
		}

		return theValue;
	}

	private Map<String, MethodDescriptor> getConstrainedMethodsAsDescriptors() {
		Map<String, MethodDescriptor> constrainedMethodDescriptors = newHashMap();

		for ( ExecutableMetaData oneExecutable : executableMetaData.values() ) {
			if ( oneExecutable.getKind() == ConstraintMetaDataKind.METHOD && oneExecutable.isConstrained() ) {
				constrainedMethodDescriptors.put(
						oneExecutable.getIdentifier(),
						(MethodDescriptor) oneExecutable.asDescriptor(
								defaultGroupSequenceIsRedefined(),
								getDefaultGroupSequence( null )
						)
				);
			}
		}

		return constrainedMethodDescriptors;
	}

	private Map<String, ConstructorDescriptor> getConstrainedConstructorsAsDescriptors() {
		Map<String, ConstructorDescriptor> constrainedMethodDescriptors = newHashMap();

		for ( ExecutableMetaData oneExecutable : executableMetaData.values() ) {
			if ( oneExecutable.getKind() == ConstraintMetaDataKind.CONSTRUCTOR && oneExecutable.isConstrained() ) {
				constrainedMethodDescriptors.put(
						oneExecutable.getIdentifier(),
						(ConstructorDescriptor) oneExecutable.asDescriptor(
								defaultGroupSequenceIsRedefined(),
								getDefaultGroupSequence( null )
						)
				);
			}
		}

		return constrainedMethodDescriptors;
	}

	private void setDefaultGroupSequenceOrProvider(List<Class<?>> defaultGroupSequence, DefaultGroupSequenceProvider<? super T> defaultGroupSequenceProvider) {

		if ( defaultGroupSequence != null && defaultGroupSequenceProvider != null ) {
			throw log.getInvalidDefaultGroupSequenceDefinitionException();
		}

		if ( defaultGroupSequenceProvider != null ) {
			this.defaultGroupSequenceProvider = defaultGroupSequenceProvider;
		}
		else if ( defaultGroupSequence != null && !defaultGroupSequence.isEmpty() ) {
			setDefaultGroupSequence( defaultGroupSequence );
		}
		else {
			setDefaultGroupSequence( Arrays.<Class<?>>asList( beanClass ) );
		}
	}

	private Set<MetaConstraint<?>> getClassLevelConstraints(Set<MetaConstraint<?>> constraints) {

		Set<MetaConstraint<?>> classLevelConstraints = partition(
				constraints,
				byElementType()
		).get( ElementType.TYPE );

		return classLevelConstraints != null ? classLevelConstraints : Collections.<MetaConstraint<?>>emptySet();
	}

	private Set<MetaConstraint<?>> buildDirectConstraintSets() {

		Set<MetaConstraint<?>> constraints = newHashSet();

		Set<Class<?>> classAndInterfaces = computeAllImplementedInterfaces( beanClass );
		classAndInterfaces.add( beanClass );

		for ( Class<?> clazz : classAndInterfaces ) {
			for ( MetaConstraint<?> oneConstraint : allMetaConstraints ) {
				if ( oneConstraint.getLocation().getBeanClass().equals( clazz ) ) {
					constraints.add( oneConstraint );
				}
			}
		}

		return Collections.unmodifiableSet( constraints );
	}

	/**
	 * Builds up the method meta data for this type
	 */
	private Map<String, ExecutableMetaData> byIdentifier(Set<ExecutableMetaData> executables) {

		Map<String, ExecutableMetaData> theValue = newHashMap();

		for ( ExecutableMetaData oneMethod : executables ) {
			theValue.put( oneMethod.getIdentifier(), oneMethod );
		}

		return theValue;
	}

	private void setDefaultGroupSequence(List<Class<?>> groupSequence) {
		defaultGroupSequence = getValidDefaultGroupSequence( groupSequence );
	}

	private List<Class<?>> getValidDefaultGroupSequence(List<Class<?>> groupSequence) {
		List<Class<?>> validDefaultGroupSequence = new ArrayList<Class<?>>();

		boolean groupSequenceContainsDefault = false;
		if ( groupSequence != null ) {
			for ( Class<?> group : groupSequence ) {
				if ( group.getName().equals( beanClass.getName() ) ) {
					validDefaultGroupSequence.add( Default.class );
					groupSequenceContainsDefault = true;
				}
				else if ( group.getName().equals( Default.class.getName() ) ) {
					throw log.getNoDefaultGroupInGroupSequenceException();
				}
				else {
					validDefaultGroupSequence.add( group );
				}
			}
		}
		if ( !groupSequenceContainsDefault ) {
			throw log.getBeanClassMustBePartOfRedefinedDefaultGroupSequenceException( beanClass.getName() );
		}
		if ( log.isTraceEnabled() ) {
			log.tracef(
					"Members of the default group sequence for bean %s are: %s.",
					beanClass.getName(),
					validDefaultGroupSequence
			);
		}

		return validDefaultGroupSequence;
	}

	private boolean hasDefaultGroupSequenceProvider() {
		return defaultGroupSequenceProvider != null;
	}

	private Partitioner<ElementType, MetaConstraint<?>> byElementType() {
		return new Partitioner<ElementType, MetaConstraint<?>>() {
			@Override
			public ElementType getPartition(MetaConstraint<?> constraint) {
				return constraint.getElementType();
			}
		};
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append( "BeanMetaDataImpl" );
		sb.append( "{beanClass=" ).append( beanClass.getSimpleName() );
		sb.append( ", constraintCount=" ).append( getMetaConstraints().size() );
		sb.append( ", cascadedPropertiesCount=" ).append( cascadedProperties.size() );
		sb.append( ", defaultGroupSequence=" ).append( getDefaultGroupSequence( null ) );
		sb.append( '}' );
		return sb.toString();
	}

	public static class BeanMetaDataBuilder<T> {

		private final ConstraintHelper constraintHelper;

		private final Class<T> beanClass;

		private final Set<BuilderDelegate> builders = newHashSet();

		private ConfigurationSource sequenceSource;

		private ConfigurationSource providerSource;

		private List<Class<?>> defaultGroupSequence;

		private DefaultGroupSequenceProvider<? super T> defaultGroupSequenceProvider;


		public BeanMetaDataBuilder(ConstraintHelper constraintHelper, Class<T> beanClass) {
			this.beanClass = beanClass;
			this.constraintHelper = constraintHelper;
		}

		public static <T> BeanMetaDataBuilder<T> getInstance(ConstraintHelper constraintHelper, Class<T> beanClass) {
			return new BeanMetaDataBuilder<T>( constraintHelper, beanClass );
		}

		public void add(BeanConfiguration<? super T> configuration) {
			if ( configuration.getBeanClass().equals( beanClass ) ) {
				if ( configuration.getDefaultGroupSequence() != null
						&& ( sequenceSource == null || configuration.getSource()
						.getPriority() >= sequenceSource.getPriority() ) ) {

					sequenceSource = configuration.getSource();
					defaultGroupSequence = configuration.getDefaultGroupSequence();
				}

				if ( configuration.getDefaultGroupSequenceProvider() != null
						&& ( providerSource == null || configuration.getSource()
						.getPriority() >= providerSource.getPriority() ) ) {

					providerSource = configuration.getSource();
					defaultGroupSequenceProvider = configuration.getDefaultGroupSequenceProvider();
				}
			}

			for ( ConstrainedElement oneConstrainedElement : configuration.getConstrainedElements() ) {
				addMetaDataToBuilder( oneConstrainedElement, builders );
			}
		}

		private void addMetaDataToBuilder(ConstrainedElement constrainableElement, Set<BuilderDelegate> builders) {

			for ( BuilderDelegate oneBuilder : builders ) {
				boolean foundBuilder = oneBuilder.add( constrainableElement );

				if ( foundBuilder ) {
					return;
				}
			}

			builders.add(
					new BuilderDelegate(
							constrainableElement,
							constraintHelper
					)
			);
		}

		public BeanMetaDataImpl<T> build() {

			Set<ConstraintMetaData> aggregatedElements = newHashSet();

			for ( BuilderDelegate oneBuilder : builders ) {
				aggregatedElements.addAll(
						oneBuilder.build(
								( defaultGroupSequence != null && defaultGroupSequence.size() > 1 ) || defaultGroupSequenceProvider != null,
								defaultGroupSequence
						)
				);
			}

			return new BeanMetaDataImpl<T>(
					beanClass,
					defaultGroupSequence,
					defaultGroupSequenceProvider,
					aggregatedElements
			);
		}
	}

	private static class BuilderDelegate {
		private final ConstraintHelper constraintHelper;
		private MetaDataBuilder propertyBuilder;
		private ExecutableMetaData.Builder methodBuilder;

		public BuilderDelegate(ConstrainedElement constrainedElement, ConstraintHelper constraintHelper) {
			this.constraintHelper = constraintHelper;

			switch ( constrainedElement.getKind() ) {
				case FIELD:
					ConstrainedField constrainedField = (ConstrainedField) constrainedElement;
					propertyBuilder = new PropertyMetaData.Builder(
							constrainedField,
							constraintHelper
					);
					break;
				case CONSTRUCTOR:
				case METHOD:
					ConstrainedExecutable constrainedExecutable = (ConstrainedExecutable) constrainedElement;
					methodBuilder = new ExecutableMetaData.Builder(
							constrainedExecutable,
							constraintHelper
					);

					if ( constrainedExecutable.isGetterMethod() ) {
						propertyBuilder = new PropertyMetaData.Builder(
								constrainedExecutable,
								constraintHelper
						);
					}
					break;
				case TYPE:
					ConstrainedType constrainedType = (ConstrainedType) constrainedElement;
					propertyBuilder = new PropertyMetaData.Builder(
							constrainedType,
							constraintHelper
					);
					break;
			}
		}

		public boolean add(ConstrainedElement constrainedElement) {
			boolean added = false;

			if ( methodBuilder != null && methodBuilder.accepts( constrainedElement ) ) {
				methodBuilder.add( constrainedElement );
				added = true;
			}

			if ( propertyBuilder != null && propertyBuilder.accepts( constrainedElement ) ) {
				propertyBuilder.add( constrainedElement );

				if ( added == false && constrainedElement.getKind() == ConstrainedElementKind.METHOD && methodBuilder == null ) {
					ConstrainedExecutable constrainedMethod = (ConstrainedExecutable) constrainedElement;
					methodBuilder = new ExecutableMetaData.Builder(
							constrainedMethod,
							constraintHelper
					);
				}

				added = true;
			}

			return added;
		}

		public Set<ConstraintMetaData> build(boolean defaultGroupSequenceRedefined, List<Class<?>> defaultGroupSequence) {

			Set<ConstraintMetaData> theValue = newHashSet();

			if ( propertyBuilder != null ) {
				theValue.add( propertyBuilder.build() );
			}

			if ( methodBuilder != null ) {
				theValue.add( methodBuilder.build() );
			}

			return theValue;
		}
	}
}
