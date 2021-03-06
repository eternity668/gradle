/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.model.internal.core;

import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;
import org.gradle.api.Action;
import org.gradle.internal.*;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.core.rule.describe.NestedModelRuleDescriptor;
import org.gradle.model.internal.core.rule.describe.SimpleModelRuleDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@ThreadSafe
abstract public class ModelCreators {

    public static <T> Builder bridgedInstance(ModelReference<T> modelReference, T instance) {
        return unmanagedInstance(modelReference, Factories.constant(instance), Actions.doNothing());
    }

    public static <T> Builder unmanagedInstance(final ModelReference<T> modelReference, final Factory<? extends T> factory) {
        return unmanagedInstance(modelReference, factory, Actions.doNothing());
    }

    public static <T> Builder unmanagedInstance(final ModelReference<T> modelReference, final Factory<? extends T> factory, Action<? super MutableModelNode> initializer) {
        @SuppressWarnings("unchecked")
        Action<? super MutableModelNode> initializers = Actions.composite(new Action<MutableModelNode>() {
            public void execute(MutableModelNode modelNode) {
                modelNode.setPrivateData(modelReference.getType(), factory.create());
            }
        }, initializer);

        return of(modelReference.getPath(), initializers)
            .withProjection(UnmanagedModelProjection.of(modelReference.getType()));
    }

    public static Builder of(ModelPath path, BiAction<? super MutableModelNode, ? super List<ModelView<?>>> initializer) {
        return new Builder(path, initializer);
    }

    public static Builder of(ModelPath path, Action<? super MutableModelNode> initializer) {
        return new Builder(path, BiActions.usingFirstArgument(initializer));
    }

    public static <T> Builder of(final ModelReference<T> modelReference, final Factory<? extends T> factory) {
        return of(modelReference.getPath(), new Action<MutableModelNode>() {
            @Override
            public void execute(MutableModelNode modelNode) {
                T value = factory.create();
                modelNode.setPrivateData(modelReference.getType(), value);
            }
        });
    }

    @NotThreadSafe
    public static class Builder {
        private final BiAction<? super MutableModelNode, ? super List<ModelView<?>>> initializer;
        private final ModelPath path;
        private final List<ModelProjection> projections = new ArrayList<ModelProjection>();
        private boolean ephemeral;
        private boolean hidden;

        private ModelRuleDescriptor modelRuleDescriptor;
        private List<ModelReference<?>> inputs = Collections.emptyList();

        private Builder(ModelPath path, BiAction<? super MutableModelNode, ? super List<ModelView<?>>> initializer) {
            this.path = path;
            this.initializer = initializer;
        }

        public Builder descriptor(String descriptor) {
            this.modelRuleDescriptor = new SimpleModelRuleDescriptor(descriptor);
            return this;
        }

        public Builder descriptor(ModelRuleDescriptor descriptor) {
            this.modelRuleDescriptor = descriptor;
            return this;
        }

        public Builder descriptor(ModelRuleDescriptor parent, ModelRuleDescriptor child) {
            this.modelRuleDescriptor = new NestedModelRuleDescriptor(parent, child);
            return this;
        }

        public Builder descriptor(ModelRuleDescriptor parent, String child) {
            this.modelRuleDescriptor = new NestedModelRuleDescriptor(parent, new SimpleModelRuleDescriptor(child));
            return this;
        }

        public Builder inputs(List<ModelReference<?>> inputs) {
            this.inputs = inputs;
            return this;
        }

        public Builder inputs(ModelReference<?>... inputs) {
            this.inputs = Arrays.asList(inputs);
            return this;
        }

        // Callers must take care
        public Builder withProjection(ModelProjection projection) {
            projections.add(projection);
            return this;
        }

        public Builder hidden(boolean flag) {
            this.hidden = flag;
            return this;
        }

        public Builder ephemeral(boolean flag) {
            this.ephemeral = flag;
            return this;
        }

        public ModelCreator build() {
            ModelProjection projection = projections.size() == 1 ? projections.get(0) : new ChainingModelProjection(projections);
            return new ProjectionBackedModelCreator(path, modelRuleDescriptor, ephemeral, hidden, inputs, projection, initializer);
        }
    }

}
