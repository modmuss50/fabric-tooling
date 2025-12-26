package fabricmc.rename;

import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class RenameTool {
	public static void main(String[] args) throws IOException {
		Path matches = Path.of("rename-tool", "rename.match");
		String input = Files.readString(matches);

		MemoryMappingTree mappings = new MemoryMappingTree();
		parseMatches(input, mappings);

		String markdown = generateMarkdown(mappings);
		Path outputPath = Path.of("rename-tool", "mapping-changes.md");
		Files.writeString(outputPath, markdown);
		System.out.println("Markdown output written to: " + outputPath);
	}

	private static String generateMarkdown(MemoryMappingTree mappings) {
		StringBuilder markdown = new StringBuilder();
		markdown.append("# API Renames\n\n");

		for (var classEntry : mappings.getClasses()) {
			String srcClassName = classEntry.getSrcName();
			String dstClassName = classEntry.getDstName(0);

			// Skip anonymous classes (e.g., Class$1, Class$2)
			if (isAnonymousClass(srcClassName) || (dstClassName != null && isAnonymousClass(dstClassName))) {
				continue;
			}

			// Track if this class has any changes
			boolean classHasChanges = false;
			StringBuilder classSection = new StringBuilder();

		// Check if class itself was renamed
		if (dstClassName != null && !srcClassName.equals(dstClassName)) {
			// Check if package changed
			String srcPackage = getPackageName(srcClassName);
			String dstPackage = getPackageName(dstClassName);

			if (srcPackage.equals(dstPackage)) {
				// Package unchanged, show full path for old name, simple name for new name
				String dstSimpleName = getSimpleClassName(dstClassName);
				classSection.append("#### `").append(srcClassName).append("` → `").append(dstSimpleName).append("`\n");
			} else {
				// Package changed, show full paths
				classSection.append("#### `").append(srcClassName).append("` → `").append(dstClassName).append("`\n");
			}
			classHasChanges = true;
		} else {
			// Class not renamed, but might have member changes
			classSection.append("#### `").append(srcClassName).append("`\n");
		}

		// Check methods
		StringBuilder methodSection = new StringBuilder();
		boolean hasMethodChanges = false;
		java.util.Set<String> seenMethods = new java.util.HashSet<>();
		for (var methodEntry : classEntry.getMethods()) {
			String srcMethodName = methodEntry.getSrcName();
			String dstMethodName = methodEntry.getDstName(0);

			if (dstMethodName != null && !srcMethodName.equals(dstMethodName)) {
				// Only show each method name once
				String methodKey = srcMethodName + " → " + dstMethodName;
				if (!seenMethods.contains(methodKey)) {
					seenMethods.add(methodKey);
					if (!hasMethodChanges) {
						hasMethodChanges = true;
					}
					methodSection.append("- `").append(srcMethodName)
						.append("` → `").append(dstMethodName).append("`\n");
				}
			}
		}

		// Check fields
		StringBuilder fieldSection = new StringBuilder();
		boolean hasFieldChanges = false;
		java.util.Set<String> seenFields = new java.util.HashSet<>();
		for (var fieldEntry : classEntry.getFields()) {
			String srcFieldName = fieldEntry.getSrcName();
			String dstFieldName = fieldEntry.getDstName(0);

			if (dstFieldName != null && !srcFieldName.equals(dstFieldName)) {
				// Only show each field name once
				String fieldKey = srcFieldName + " → " + dstFieldName;
				if (!seenFields.contains(fieldKey)) {
					seenFields.add(fieldKey);
					if (!hasFieldChanges) {
						if (hasMethodChanges) {
							fieldSection.append("\n");
						}
						hasFieldChanges = true;
					}
					fieldSection.append("- `").append(srcFieldName).append("` → `").append(dstFieldName).append("`\n");
				}
			}
		}

			// Only include this class if it or its members have changes
			if (classHasChanges || hasMethodChanges || hasFieldChanges) {
				markdown.append(classSection);
				if (hasMethodChanges) {
					markdown.append(methodSection);
				}
				if (hasFieldChanges) {
					markdown.append(fieldSection);
				}
				markdown.append("\n");
			}
		}

		return markdown.toString();
	}

	private static void parseMatches(String input, MemoryMappingTree mappings) throws IOException {
		// Initialize namespaces: source and destination
		mappings.visitNamespaces("old", List.of("renamed"));

		String[] lines = input.split("\n");
		String currentClass = null;

		for (String line : lines) {
			if (line.isEmpty()) {
				continue;
			}

			// Count leading tabs to determine indentation level
			int indent = 0;
			while (indent < line.length() && line.charAt(indent) == '\t') {
				indent++;
			}

			String trimmed = line.substring(indent);
			String[] parts = trimmed.split("\t");

			if (parts.length < 3) {
				continue; // Skip invalid lines (like ma entries)
			}

			String type = parts[0];
			String source = parts[1];
			String dest = parts[2];

			switch (type) {
				case "c": // Class
					// Extract class name from descriptor (Lpath/to/Class; -> path/to/Class)
					currentClass = extractClassName(source);
					String destClass = extractClassName(dest);
					mappings.visitClass(currentClass);
					mappings.visitDstName(net.fabricmc.mappingio.MappedElementKind.CLASS, 0, destClass);
					break;

				case "m": // Method
					if (currentClass != null) {
						// Parse method signature: name(params)returnType
						String srcMethodName = extractMethodName(source);
						String srcMethodDesc = extractMethodDescriptor(source);
						String destMethodName = extractMethodName(dest);

						mappings.visitMethod(srcMethodName, srcMethodDesc);
						mappings.visitDstName(net.fabricmc.mappingio.MappedElementKind.METHOD, 0, destMethodName);
					}
					break;

				case "f": // Field
					if (currentClass != null) {
						// Parse field: name;;descriptor
						String[] srcFieldParts = source.split(";;");
						String[] destFieldParts = dest.split(";;");

						if (srcFieldParts.length >= 2 && destFieldParts.length >= 1) {
							String srcFieldName = srcFieldParts[0];
							String srcFieldDesc = srcFieldParts[1];
							String destFieldName = destFieldParts[0];

							mappings.visitField(srcFieldName, srcFieldDesc);
							mappings.visitDstName(net.fabricmc.mappingio.MappedElementKind.FIELD, 0, destFieldName);
						}
					}
					break;
			}
		}
	}

	private static String extractClassName(String descriptor) {
		// Remove leading 'L' and trailing ';'
		if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
			return descriptor.substring(1, descriptor.length() - 1);
		}
		return descriptor;
	}

	private static String getPackageName(String className) {
		int lastSlash = className.lastIndexOf('/');
		if (lastSlash > 0) {
			return className.substring(0, lastSlash);
		}
		return ""; // No package (default package)
	}

	private static String getSimpleClassName(String className) {
		int lastSlash = className.lastIndexOf('/');
		if (lastSlash >= 0) {
			return className.substring(lastSlash + 1);
		}
		return className;
	}

	private static String extractMethodName(String methodSig) {
		// Extract name from "name(params)returnType"
		int parenIndex = methodSig.indexOf('(');
		if (parenIndex > 0) {
			return methodSig.substring(0, parenIndex);
		}
		return methodSig;
	}

	private static String extractMethodDescriptor(String methodSig) {
		// Extract descriptor from "name(params)returnType"
		int parenIndex = methodSig.indexOf('(');
		if (parenIndex >= 0) {
			return methodSig.substring(parenIndex);
		}
		return methodSig;
	}

	private static boolean isAnonymousClass(String className) {
		// Check if class name ends with $<digit> (e.g., Class$1, Class$2)
		int dollarIndex = className.lastIndexOf('$');
		if (dollarIndex >= 0 && dollarIndex < className.length() - 1) {
			String afterDollar = className.substring(dollarIndex + 1);
			// Check if all characters after $ are digits
			return afterDollar.matches("\\d+");
		}
		return false;
	}
}
