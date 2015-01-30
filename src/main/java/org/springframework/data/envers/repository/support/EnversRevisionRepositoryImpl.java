/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.envers.repository.support;

import org.hibernate.envers.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.history.AnnotationRevisionMetadata;
import org.springframework.data.history.Revision;
import org.springframework.data.history.RevisionMetadata;
import org.springframework.data.history.Revisions;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.history.support.RevisionEntityInformation;
import org.springframework.util.Assert;

import javax.persistence.EntityManager;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;

/**
 * Repository implementation using Hibernate Envers to implement revision specific query methods.
 * 
 * @author Oliver Gierke
 * @author Philipp Huegelmeyer
 */
public class EnversRevisionRepositoryImpl<T, ID extends Serializable, N extends Number & Comparable<N>> extends
        SimpleJpaRepository<T, ID> implements EnversRevisionRepository<T, ID, N> {

	private final EntityInformation<T, ?> entityInformation;
	private final RevisionEntityInformation revisionEntityInformation;
	private final EntityManager entityManager;

	/**
	 * Creates a new {@link EnversRevisionRepositoryImpl} using the given {@link JpaEntityInformation},
	 * {@link RevisionEntityInformation} and {@link EntityManager}.
	 * 
	 * @param entityInformation must not be {@literal null}.
	 * @param revisionEntityInformation must not be {@literal null}.
	 * @param entityManager must not be {@literal null}.
	 */
	public EnversRevisionRepositoryImpl(JpaEntityInformation<T, ?> entityInformation,
			RevisionEntityInformation revisionEntityInformation, EntityManager entityManager) {

		super(entityInformation, entityManager);

		Assert.notNull(revisionEntityInformation);

		this.entityInformation = entityInformation;
		this.revisionEntityInformation = revisionEntityInformation;
		this.entityManager = entityManager;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.history.RevisionRepository#findLastChangeRevision(java.io.Serializable)
	 */
	@SuppressWarnings("unchecked")
	public Revision<N, T> findLastChangeRevision(ID id) {

		Class<T> type = entityInformation.getJavaType();
		AuditReader reader = AuditReaderFactory.get(entityManager);

		List<Number> revisions = reader.getRevisions(type, id);

		if (revisions.isEmpty()) {
			return null;
		}

		N latestRevision = (N) revisions.get(revisions.size() - 1);

		Class<?> revisionEntityClass = revisionEntityInformation.getRevisionEntityClass();

		Object revisionEntity = reader.findRevision(revisionEntityClass, latestRevision);
		RevisionMetadata<N> metadata = (RevisionMetadata<N>) getRevisionMetadata(revisionEntity);
		return new Revision<N, T>(metadata, reader.find(type, id, latestRevision));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.history.RevisionRepository#findRevisions(java.io.Serializable)
	 */
	@SuppressWarnings("unchecked")
	public Revisions<N, T> findRevisions(ID id) {

		Class<T> type = entityInformation.getJavaType();
		AuditReader reader = AuditReaderFactory.get(entityManager);
		List<? extends Number> revisionNumbers = reader.getRevisions(type, id);

		return revisionNumbers.isEmpty() ? new Revisions<N, T>(Collections.EMPTY_LIST) : getEntitiesForRevisions(
				(List<N>) revisionNumbers, id, reader);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.history.RevisionRepository#findRevisions(java.io.Serializable, org.springframework.data.domain.Pageable)
	 */
	@SuppressWarnings("unchecked")
	public Page<Revision<N, T>> findRevisions(ID id, Pageable pageable) {

		Class<T> type = entityInformation.getJavaType();
		AuditReader reader = AuditReaderFactory.get(entityManager);
		List<Number> revisionNumbers = reader.getRevisions(type, id);

		if (pageable.getOffset() > revisionNumbers.size()) {
			return new PageImpl<Revision<N, T>>(Collections.<Revision<N, T>> emptyList(), pageable, 0);
		}

		int upperBound = pageable.getOffset() + pageable.getPageSize();
		upperBound = upperBound > revisionNumbers.size() ? revisionNumbers.size() : upperBound;

		List<? extends Number> subList = revisionNumbers.subList(pageable.getOffset(), upperBound);
		Revisions<N, T> revisions = getEntitiesForRevisions((List<N>) subList, id, reader);

		return new PageImpl<Revision<N, T>>(revisions.getContent(), pageable, revisionNumbers.size());
	}

    @Override
    @SuppressWarnings("unchecked")
    public Revision<N, T> findEntityAtRevision(ID id, N revision) {

        Class<T> type = entityInformation.getJavaType();
        AuditReader reader = AuditReaderFactory.get(entityManager);

        List<Number> revisions = reader.getRevisions(type, id);

        if (revisions.isEmpty()) {
            return null;
        }

        Class<?> revisionEntityClass = revisionEntityInformation.getRevisionEntityClass();

        Object revisionEntity = reader.findRevision(revisionEntityClass, revision);
        RevisionMetadata<N> metadata = (RevisionMetadata<N>) getRevisionMetadata(revisionEntity);
        return new Revision<N, T>(metadata, reader.find(type, id, revision));

    }

	/**
	 * Returns the entities in the given revisions for the entitiy with the given id.
	 * 
	 * @param revisionNumbers
	 * @param id
	 * @param reader
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private Revisions<N, T> getEntitiesForRevisions(List<N> revisionNumbers, ID id, AuditReader reader) {

		Class<T> type = entityInformation.getJavaType();
		Map<N, T> revisions = new HashMap<N, T>(revisionNumbers.size());

		Class<?> revisionEntityClass = revisionEntityInformation.getRevisionEntityClass();
		Map<Number, Object> revisionEntities = (Map<Number, Object>) reader.findRevisions(revisionEntityClass,
				new HashSet<Number>(revisionNumbers));

		for (Number number : revisionNumbers) {
			revisions.put((N) number, reader.find(type, id, number));
		}

		return new Revisions<N, T>(toRevisions(revisions, revisionEntities));
	}

	@SuppressWarnings("unchecked")
	private List<Revision<N, T>> toRevisions(Map<N, T> source, Map<Number, Object> revisionEntities) {

		List<Revision<N, T>> result = new ArrayList<Revision<N, T>>();

		for (Entry<N, T> revision : source.entrySet()) {

			N revisionNumber = revision.getKey();
			T entity = revision.getValue();
			RevisionMetadata<N> metadata = (RevisionMetadata<N>) getRevisionMetadata(revisionEntities.get(revisionNumber));
			result.add(new Revision<N, T>(metadata, entity));
		}

		Collections.sort(result);
		return Collections.unmodifiableList(result);
	}

	/**
	 * Returns the {@link RevisionMetadata} wrapper depending on the type of the given object.
	 * 
	 * @param object
	 * @return
	 */
	private RevisionMetadata<?> getRevisionMetadata(Object object) {
		if (object instanceof DefaultRevisionEntity) {
			return new DefaultRevisionMetadata((DefaultRevisionEntity) object);
		} else {
			return new AnnotationRevisionMetadata<N>(object, RevisionNumber.class, RevisionTimestamp.class);
		}
	}
}
