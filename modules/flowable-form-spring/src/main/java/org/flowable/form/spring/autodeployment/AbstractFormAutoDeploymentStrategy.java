/* Licensed under the Apache License, Version 2.0 (the "License");
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

package org.flowable.form.spring.autodeployment;

import java.time.Duration;

import org.flowable.common.engine.impl.lock.LockManager;
import org.flowable.common.spring.CommonAutoDeploymentStrategy;
import org.flowable.form.engine.FormEngine;

/**
 * Abstract base class for implementations of {@link org.flowable.common.spring.AutoDeploymentStrategy AutoDeploymentStrategy}.
 *
 * @author Tiese Barrell
 * @author Filip Hrisafov
 */
public abstract class AbstractFormAutoDeploymentStrategy extends CommonAutoDeploymentStrategy<FormEngine> {

    public AbstractFormAutoDeploymentStrategy() {
    }

    public AbstractFormAutoDeploymentStrategy(boolean useLockForDeployments, Duration deploymentLockWaitTime) {
        super(useLockForDeployments, deploymentLockWaitTime);
    }

    @Override
    protected LockManager getLockManager(FormEngine engine, String deploymentNameHint) {
        return engine.getFormEngineConfiguration().getLockManager(deploymentNameHint);
    }

}
