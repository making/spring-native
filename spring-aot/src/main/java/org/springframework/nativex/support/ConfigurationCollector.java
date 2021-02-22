/*
 * Copyright 2020 the original author or authors.
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
package org.springframework.nativex.support;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.nativex.AotOptions;
import org.springframework.nativex.domain.init.InitializationDescriptor;
import org.springframework.nativex.domain.proxies.ProxiesDescriptor;
import org.springframework.nativex.domain.proxies.ProxyDescriptor;
import org.springframework.nativex.domain.reflect.ClassDescriptor;
import org.springframework.nativex.domain.reflect.FieldDescriptor;
import org.springframework.nativex.domain.reflect.MethodDescriptor;
import org.springframework.nativex.domain.reflect.ReflectionDescriptor;
import org.springframework.nativex.domain.resources.ResourcesDescriptor;
import org.springframework.nativex.hint.Flag;
import org.springframework.nativex.type.Type;
import org.springframework.nativex.type.TypeSystem;

/**
 * Centralized collector of all computed configuration, can be used to produce JSON files, can optionally forward that info to GraalVM.
 * 
 * @author Andy Clement
 */
public class ConfigurationCollector {

	private final AotOptions aotOptions;
	
	private static Log logger = LogFactory.getLog(ConfigurationCollector.class);

	private ReflectionDescriptor reflectionDescriptor = new ReflectionDescriptor();

	private ResourcesDescriptor resourcesDescriptor = new ResourcesDescriptor();

	private ProxiesDescriptor proxiesDescriptor = new ProxiesDescriptor();

	private InitializationDescriptor initializationDescriptor = new InitializationDescriptor();

	private Connector graalVMConnector;
	
	private Map<String,byte[]> newResourceFiles = new HashMap<>();
	
	private TypeSystem ts;

	public ConfigurationCollector(AotOptions aotOptions) {
		this.aotOptions = aotOptions;
	}

	public ProxiesDescriptor getProxyDescriptors() {
		return proxiesDescriptor;
	}

	public ReflectionDescriptor getReflectionDescriptor() {
		return reflectionDescriptor;
	}

	public ResourcesDescriptor getResourcesDescriptors() {
		return resourcesDescriptor;
	}
	
	public byte[] getResources(String name) {
		return newResourceFiles.get(name);
	}

	public InitializationDescriptor getInitializationDescriptor() {
		return initializationDescriptor;
	}

	public void setGraalConnector(Connector graalConnector) {
		this.graalVMConnector = graalConnector;
	}
	
	public void setTypeSystem(TypeSystem ts) {
		this.ts = ts;
	}

	private boolean checkTypes(List<String> types, Predicate<Type> test) {
		for (int i = 0; i < types.size(); i++) {
			String className = types.get(i);
			Type clazz = ts.resolveDotted(className, true);
			if (!test.test(clazz)) {
				return false;
			}
		}
		return true;
	}

	public boolean addProxy(List<String> interfaceNames, boolean verify) {
		if (verify) {
			if (!checkTypes(interfaceNames, t -> t!=null && t.isInterface())) {
				return false;
			}
		}
		if (graalVMConnector!=null) {
			graalVMConnector.addProxy(interfaceNames);
		}
		proxiesDescriptor.add(ProxyDescriptor.of(interfaceNames));
		return true;
	}

	public static String[] subarray(String[] array) {
		if (array.length == 1) {
			return null;
		} else {
			return Arrays.copyOfRange(array, 1, array.length);
		}
	}
	
	private void writeNativeImageProperties(File file) throws IOException {
		String content = getNativeImagePropertiesContent();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(content.getBytes());
		}
	}
	
	public String getNativeImagePropertiesContent() {
		StringBuilder s = new StringBuilder();
		// From the existing native-image.properties
		s.append("Args = --allow-incomplete-classpath --report-unsupported-elements-at-runtime --no-fallback --no-server --install-exit-handlers -H:+InlineBeforeAnalysis");
		if (!initializationDescriptor.getBuildtimeClasses().isEmpty() || 
			!initializationDescriptor.getBuildtimePackages().isEmpty()) {
			s.append(" \\\n");
			s.append("--initialize-at-build-time=");
			int i = 0;
			for (String name: initializationDescriptor.getBuildtimeClasses()) {
				if (i>0) {
					s.append(",");
				}
				s.append(name);
				i++;
			}
			for (String name: initializationDescriptor.getBuildtimePackages()) {
				if (i>0) {
					s.append(",");
				}
				s.append(name);
				i++;
			}
		}
		if (!initializationDescriptor.getRuntimeClasses().isEmpty() || 
			!initializationDescriptor.getRuntimePackages().isEmpty()) {
			s.append(" \\\n");
			s.append("--initialize-at-run-time=");
			int i = 0;
			for (String name: initializationDescriptor.getRuntimeClasses()) {
				if (i>0) {
					s.append(",");
				}
				s.append(name);
				i++;
			}
			for (String name: initializationDescriptor.getRuntimePackages()) {
				if (i>0) {
					s.append(",");
				}
				s.append(name);
				i++;
			}
		}
		s.append("\n");
		return s.toString();
	}

	public void addResourcesDescriptor(ResourcesDescriptor resourcesDescriptor) {
		this.resourcesDescriptor.merge(resourcesDescriptor);
		if (graalVMConnector != null) {
			graalVMConnector.addResourcesDescriptor(resourcesDescriptor);
		}
	}

	public ReflectionDescriptor addReflectionDescriptor(ReflectionDescriptor reflectionDescriptor) {
		ReflectionDescriptor filteredReflectionDescriptor = filterVerified(reflectionDescriptor);
		this.reflectionDescriptor.merge(filteredReflectionDescriptor);
		if (graalVMConnector != null) {
			graalVMConnector.addReflectionDescriptor(filteredReflectionDescriptor);
		}
		return filteredReflectionDescriptor;
	}
	
	private boolean areMembersSpecified(ClassDescriptor cd) {
		List<MethodDescriptor> methods = cd.getMethods();
		if (methods != null && !methods.isEmpty()) {
			return true;
		}
		List<FieldDescriptor> fields = cd.getFields();
		if (fields != null && !fields.isEmpty()) {
			return true;
		}
		if (cd.getFlags()!=null && (cd.getFlags().contains(Flag.allDeclaredConstructors) || cd.getFlags().contains(Flag.allPublicConstructors))) {
			return true;
		}
		return false;
	}

	private ReflectionDescriptor filterVerified(ReflectionDescriptor reflectionDescriptor2) {
		boolean anyFailed = false;
		List<ClassDescriptor> verified = new ArrayList<>();
		List<ClassDescriptor> classDescriptors = reflectionDescriptor2.getClassDescriptors();
		for (ClassDescriptor classDescriptor: classDescriptors) {
			boolean verifyType = verifyType(classDescriptor);
			if (!verifyType) {
				if (aotOptions.isDebugVerify()) {
					logger.debug("FAILED: filtering out "+classDescriptor.getName());
				}
				// Give up now
				anyFailed=true;
				continue;
			}
			if (areMembersSpecified(classDescriptor)) {
				if (!verifyMembers(classDescriptor)) {
					logger.debug("Stripped down to a base class descriptor for "+classDescriptor.getName());
					Set<Flag> existingFlags = classDescriptor.getFlags();
					classDescriptor = ClassDescriptor.of(classDescriptor.getName());
//					classDescriptor.setFlags(Flags.
					anyFailed=true;
				}
			}
			verified.add(classDescriptor);
		}
		return anyFailed?new ReflectionDescriptor(verified):reflectionDescriptor2;
	}

	private boolean verifyType(ClassDescriptor classDescriptor) {
		Type t = ts.resolveDotted(classDescriptor.getName(),true);
		if (t== null) {
			if (aotOptions.isDebugVerify()) {
				logger.debug("FAILED VERIFICATION type missing "+classDescriptor.getName());
			}
			return false;
		} else {
			return t.verifyType(aotOptions.isDebugVerify());
		}
	}

	private boolean verifyMembers(ClassDescriptor classDescriptor) {
		Type t = ts.resolveDotted(classDescriptor.getName(),true);
		if (t== null) {
			if (aotOptions.isDebugVerify()) {
				logger.debug("FAILED VERIFICATION type missing "+classDescriptor.getName());
			}
			return false;
		} else {
			return t.verifyMembers(aotOptions.isDebugVerify());
		}
	}

	public void addClassDescriptor(ClassDescriptor classDescriptor) {
		boolean verifyType = verifyType(classDescriptor);
		if (!verifyType) {
			// Give up now
			return;
		}
		if (areMembersSpecified(classDescriptor)) {
			if (!verifyMembers(classDescriptor)) {
				Set<Flag> existingFlags = classDescriptor.getFlags();
				classDescriptor = ClassDescriptor.of(classDescriptor.getName());
//				classDescriptor.setFlags(existingFlags);
			}
		}
		reflectionDescriptor.merge(classDescriptor);
		// add it to existing refl desc stuff...
		if (graalVMConnector != null) {
			graalVMConnector.addClassDescriptor(classDescriptor);
		}
	}

	public void registerResource(String resourceName, byte[] bytes) {
		resourcesDescriptor.add(resourceName);
		newResourceFiles.put(resourceName, bytes);
		if (graalVMConnector != null) {
			graalVMConnector.registerResource(resourceName, new ByteArrayInputStream(bytes));
		}
	}
	
	public void addResource(String pattern, boolean isBundle) {
		if (isBundle) {
			resourcesDescriptor.addBundle(pattern);
		} else {
			resourcesDescriptor.add(pattern);
		}
		if (graalVMConnector != null) {
			graalVMConnector.addResource(pattern, isBundle);
		}
	}

	public void initializeAtBuildTime(Type type) {
		initializeAtBuildTime(type.getDottedName());
	}

	public void initializeAtRunTime(Type type) {
		initializeAtRunTime(type.getDottedName());
	}

	public void initializeAtRunTime(List<Type> types) {
		for (Type type: types) {
			initializationDescriptor.addRuntimeClass(type.getDottedName());
		}
		if (graalVMConnector != null) {
			graalVMConnector.initializeAtRunTime(types);
		}
	}

	public void initializeAtBuildTime(List<Type> types) {
		for (Type type: types) {
			initializationDescriptor.addBuildtimeClass(type.getDottedName());
		}
		for (Type type: types) {
			initializeAtBuildTime(type.getDottedName());
		}
	}

	public void initializeAtRunTime(String... typenames) {
		for (String typename: typenames) {
			initializationDescriptor.addRuntimeClass(typename);
		}
		if (graalVMConnector != null) {
			graalVMConnector.initializeAtRunTime(typenames);
		}
	}

	public void initializeAtBuildTime(String... typenames) {
		for (String typename: typenames) {
			initializationDescriptor.addBuildtimeClass(typename);
		}
		if (graalVMConnector != null) {
			graalVMConnector.initializeAtBuildTime(typenames);
		}
	}

	public void initializeAtBuildTimePackages(String... packageNames) {
		for (String packageName: packageNames) {
			initializationDescriptor.addBuildtimePackage(packageName);
		}
		if (graalVMConnector != null) {
			graalVMConnector.initializeAtBuildTimePackages(packageNames);
		}
	}

	public void initializeAtRunTimePackages(String... packageNames) {
		for (String packageName: packageNames) {
			initializationDescriptor.addRuntimePackage(packageName);
		}
		if (graalVMConnector != null) {
			graalVMConnector.initializeAtRunTimePackages(packageNames);
		}
	}

	public InputStream getNativeImagePropertiesInputStream() {
		return new ByteArrayInputStream(getNativeImagePropertiesContent().getBytes());
	}

}
