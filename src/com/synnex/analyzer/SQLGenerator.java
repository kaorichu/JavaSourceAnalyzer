package com.synnex.analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.CompilationUnit.Storage;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;

class SQLGenerator extends VoidVisitorAdapter<Set<String>> {
	static final Logger logger = LoggerFactory.getLogger(SQLGenerator.class);
	private static final String UNKNOWN = "Unknown";
	private static final List<String> fileTypes = List.of("FORM", "VO", "ACTION", "MODEL", "DAO", "BD",
			"SERVICE");
	private static final String FILE_CALLBACK_ORDER = "file {} callback order: {} on event: {}";
	private final Set<String> ipmortSet = new HashSet<>();
	private final Set<String> relationSet = new HashSet<>();
	private final Set<String> skipSet;
	private final JavaParserFacade javaParserFacade;
	private final int targetTables;
	private final List<SourceDir> srcDirs;
	private int callbackTimer = 0;
	private int exceptionCount = 0;
	private boolean multiType = false;
	private String oriFilePath;
	private String filePath;
	private String packageName = "";
	private String className;

	public SQLGenerator(TypeSolver typeSolver, int targetTables, List<SourceDir> srcDirs, Set<String> skipSet) {
		this.targetTables = targetTables;
		this.javaParserFacade = JavaParserFacade.get(typeSolver);
		this.srcDirs = srcDirs;
		this.skipSet = skipSet;
	}

	@Override
	public void visit(CompilationUnit cu, Set<String> sqlStatements) {
		oriFilePath = cu.getStorage().map(Storage::getPath).map(Path::toString).orElse("unknown");
		filePath = oriFilePath.replaceAll(Pattern.quote(Initer.ROOT_SRC_DIR) + "2?", "src");
		logger.debug("start generate sql from {}", filePath);
		logger.trace(FILE_CALLBACK_ORDER, filePath, callbackTimer++, "CompilationUnit");
		multiType = cu.getTypes().size() > 1;
		if(multiType) logger.trace("file {} declaration size: {}", filePath, cu.getTypes().size());
		super.visit(cu, sqlStatements);
		logger.debug("end generate sql from {}", filePath);
	}
	
	@Override
	public void visit(PackageDeclaration pd, Set<String> sqlStatements) {
		logger.trace(FILE_CALLBACK_ORDER, filePath, callbackTimer++, "PackageDeclaration");
		packageName = pd.getNameAsString();
	}

	@Override
	public void visit(ClassOrInterfaceDeclaration c, Set<String> sqlStatements) {
		logger.trace(FILE_CALLBACK_ORDER, filePath, callbackTimer++, "ClassOrInterfaceDeclaration");
		className = packageName.isEmpty() ? c.getNameAsString() : packageName + "." + c.getNameAsString();
		boolean mainClass = !multiType || c.isPublic();
		if((targetTables & Initer.BS_SOURCE_LIST) > 0 && mainClass) {
			// Add to BS_SOURCE_LIST
			String mainParent = getMainClassDeclaration(c.getExtendedTypes());
			String mainImplements = getMainClassDeclaration(c.getImplementedTypes());
			String fileSize;
			String lineCount;
			try(var lines = Files.lines(Path.of(oriFilePath))) {
				fileSize = "'" + Files.size(Path.of(oriFilePath)) + "'";
				lineCount = "'" + lines.count() + "'";
			} catch (IOException e) {
				fileSize = null;
				lineCount = null;
			}
			String fileType = fileTypes.stream().filter(s -> filePath.contains(s.toLowerCase())).findFirst()
					.orElse("UNKNOW");
			String format = String.format(
					"INSERT INTO BS_SOURCE_LIST (BS, WAR, FILE_NAME, EXT_NAME, PACKAGE, CLASS_NAME, FILE_TYPE, EXTENDS, IMPLEMENTS, FILE_SIZE, LINE_COUNT) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s', %s, %s, %s, %s);",
					JavaSourceAnalyzer.BS, JavaSourceAnalyzer.WAR, filePath, filePath.substring(filePath.lastIndexOf('.')), packageName, className, fileType, mainParent, mainImplements, fileSize, lineCount);
			if(!skipSet.contains(format)) {
				sqlStatements.add(format);
			}
		}
		if ((targetTables & Initer.BS_SOURCE_RELATION) > 0) {
			// Add to BS_SOURCE_RELATION for extends and implements
			c.getExtendedTypes().stream().map(javaParserFacade::convertToUsage)
					.map(ResolvedType::asReferenceType).map(ResolvedReferenceType::getQualifiedName)
					.filter(relationSet::add)
					.map(type -> String.format(
							"INSERT INTO BS_SOURCE_RELATION (BS, WAR, FILE_NAME, REF_KEY, REF_FILE_NAME, RELATION_TYPE) VALUES ('%s', '%s','%s', '%s', '%s', 'E');",
							JavaSourceAnalyzer.BS, JavaSourceAnalyzer.WAR, filePath, type, findClassFilePath(type)))
					.filter(Predicate.not(skipSet::contains))
					.forEach(sqlStatements::add);
			c.getImplementedTypes().stream().map(javaParserFacade::convertToUsage)
					.map(ResolvedType::asReferenceType).map(ResolvedReferenceType::getQualifiedName)
					.filter(relationSet::add)
					.map(type -> String.format(
							"INSERT INTO BS_SOURCE_RELATION (BS, WAR, FILE_NAME, REF_KEY, REF_FILE_NAME, RELATION_TYPE) VALUES ('%s', '%s','%s', '%s', '%s', 'P');",
							JavaSourceAnalyzer.BS, JavaSourceAnalyzer.WAR, filePath, type, findClassFilePath(type)))
					.filter(Predicate.not(skipSet::contains))
					.forEach(sqlStatements::add);
		}
		c.getPermittedTypes().forEach(p -> p.accept(this, sqlStatements));
		c.getTypeParameters().forEach(p -> p.accept(this, sqlStatements));
		c.getMembers().forEach(p -> p.accept(this, sqlStatements));
	}

	private String getMainClassDeclaration(NodeList<ClassOrInterfaceType> list) {
		try {
			return list.isEmpty() ? "null"
					: "'" + javaParserFacade.convertToUsage(list.get(0)).describe() + "'";
		} catch (Exception e) {
			logger.warn("convert type fail on extend type parse", e);
			increaseExceptonCount();
			return "'" + list.get(0) + "'";
		}
	}

	// Add to BS_SOURCE_RELATION for imports
	@Override
	public void visit(ImportDeclaration imp, Set<String> sqlStatements) {
		logger.trace(FILE_CALLBACK_ORDER, filePath, callbackTimer++, "ImportDeclaration");
		if(imp.isAsterisk()) return;
		String fullyQualifiedName = imp.getNameAsString();
		ipmortSet.add(fullyQualifiedName);
		if((targetTables & Initer.BS_SOURCE_RELATION) > 0 && relationSet.add(fullyQualifiedName)) {
			String refClassName = findClassFilePath(fullyQualifiedName);
			String remark = "null";
			if(refClassName.equals(UNKNOWN)) {
				refClassName = "N/A";
				remark  = "'非自行開發'";
			}
			String format = String.format(
					"INSERT INTO BS_SOURCE_RELATION (BS, WAR, FILE_NAME, REF_KEY, REF_FILE_NAME, RELATION_TYPE, REMARK) VALUES ('%s', '%s', '%s', '%s', '%s', 'I', %s);",
					JavaSourceAnalyzer.BS, JavaSourceAnalyzer.WAR, filePath, imp.getNameAsString(), refClassName, remark);
			if(!skipSet.contains(format)) {
				sqlStatements.add(format);
			}
		}
	}

	@Override
	public void visit(ClassOrInterfaceType n, Set<String> sqlStatements) {
		logger.trace(FILE_CALLBACK_ORDER, filePath, callbackTimer++, "ClassOrInterfaceType");
		if(n.getNameAsString().contains(className)) {
			return;//no need to save class itself.
		}
		ResolvedType type = convertToUsage(n);
		if(type == null) return;
		addstatement(type, sqlStatements);
		if(type.isReferenceType()) {
			type.asReferenceType().getTypeParametersMap().forEach(t -> addstatement(t.b, sqlStatements));
		}
	}

	// Visit methods
	@Override
	public void visit(MethodDeclaration md, Set<String> sqlStatements) {
		logger.trace(FILE_CALLBACK_ORDER, filePath, callbackTimer++, "MethodDeclaration");
		findClassName(md);
		if((targetTables & Initer.BS_SOURCE_METHODS) > 0) {
			String methodName = md.getNameAsString();
			String param = md.getParameters().stream().map(p -> p.getTypeAsString() + " " + p.getNameAsString())
					.collect(Collectors.joining(","));
			if (param.isBlank()) {
				param = "N/A";
			}
			// Add to BS_SOURCE_METHODS
			String format = String.format(
					"INSERT INTO BS_SOURCE_METHODS (BS, WAR, FILE_NAME, METHOD, PARAMS, SCOPE, PACKAGE) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s');",
					JavaSourceAnalyzer.BS, JavaSourceAnalyzer.WAR, filePath, methodName, param, md.getAccessSpecifier().asString(), packageName);
			if(!skipSet.contains(format)) {
				sqlStatements.add(format);
			}
		}
		super.visit(md, sqlStatements);
	}

	@Override
	public void visit(FieldAccessExpr f, Set<String> sqlStatements) {
		logger.trace(FILE_CALLBACK_ORDER, filePath, callbackTimer++, "FieldAccessExpr");
		super.visit(f, sqlStatements);
		ResolvedType type;
		SymbolReference<? extends ResolvedTypeDeclaration> result;
		if (f.getScope().isNameExpr()) {
			result = javaParserFacade.getSymbolSolver().solveType(f.getScope().toString(),
					JavaParserFactory.getContext(f, javaParserFacade.getTypeSolver()));
		} else {
			result = javaParserFacade.getSymbolSolver().solveType(f.toString(),
					JavaParserFactory.getContext(f, javaParserFacade.getTypeSolver()));
		}
		if(result.isSolved()) {
			type = new ReferenceTypeImpl(result.getCorrespondingDeclaration().asReferenceType());
		}else {
			return;
		}
		addstatement(type, sqlStatements);
	}

	@Override
	public void visit(MethodCallExpr mce, Set<String> sqlStatements) {
		logger.trace(FILE_CALLBACK_ORDER, filePath, callbackTimer++, "MethodCallExpr");
		if((targetTables & (Initer.BS_SOURCE_CALL_METHODS | Initer.BS_SOURCE_RELATION)) > 0) {
			// Add to BS_SOURCE_CALL_METHODS for method calls
			String refClassName;
			try {
				var type = mce.getScope().map(javaParserFacade::getType);
				if((targetTables & Initer.BS_SOURCE_RELATION) > 0 && type.isPresent()) {
					addstatement(type.get(), sqlStatements);
				}
				refClassName = type.map(ResolvedType::describe).orElse(className);
			} catch (Exception e) {
				logger.warn("exception on try to get ref className from " + mce.toString() + " at class " + className, e);
				increaseExceptonCount();
				refClassName = "unknown";
			}
			if((targetTables & Initer.BS_SOURCE_CALL_METHODS) > 0) {
				String callMethod = mce.getNameAsString();
				String refType = ipmortSet.contains(refClassName.split("<")[0]) ? "I" : "N";
				String format = String.format(
						"INSERT INTO BS_SOURCE_CALL_METHODS (BS, WAR, FILE_NAME, REF_CLASS_NAME, CALL_METHOD, REF_TYPE, PACKAGE) VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s');",
						JavaSourceAnalyzer.BS, JavaSourceAnalyzer.WAR, filePath, refClassName, callMethod, refType, packageName);
				if(!skipSet.contains(format)) {
					sqlStatements.add(format);
				}
			}
		}
		super.visit(mce, sqlStatements);
	}

	private void findClassName(MethodDeclaration md) {
		if (className != null) {
			return;
		}
		var op = md.getParentNode();
		while(op.isPresent() && !(op.get() instanceof ClassOrInterfaceDeclaration)) {
			op = op.get().getParentNode();
		}
		if (op.isPresent() && !(op.get() instanceof ClassOrInterfaceDeclaration)) {
			var p = op.get();
			var c = (ClassOrInterfaceDeclaration) p;
			var pp = p.getParentNode();
			while(pp.isPresent()) {
				p = pp.get();
				pp = p.getParentNode();
			}
			if (p instanceof CompilationUnit) {
				CompilationUnit cu = (CompilationUnit) p;
				packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
			}else {
				packageName = "";
			}
			className = packageName.isEmpty() ? c.getNameAsString() : packageName + "." + c.getNameAsString();
		}
	}

	private ResolvedType convertToUsage(Type type) {
		try {
			return javaParserFacade.convertToUsage(type);
		}catch(Exception e) {
			logger.warn("convert type fail", e);
			increaseExceptonCount();
			return null;
		}
	}

	private void addstatement(ResolvedType type, Set<String> sqlStatements) {
		if((targetTables & Initer.BS_SOURCE_RELATION) > 0 && type != null) {
			String typeName;
			if(type.isArray()) {
				addstatement(type.asArrayType().getComponentType(), sqlStatements);
				return;
			}else if(type.isPrimitive()) {
				typeName = type.asPrimitive().name();
			}else if(type.isReferenceType()) {
				typeName = type.asReferenceType().getQualifiedName();
			}else {
				return;
			}
			if(!relationSet.add(typeName)) {
				return;
			}
			String refClassName = findClassFilePath(typeName);
			//pass same file relation
			if(filePath.equals(refClassName)) {
				return;
			}
			String remark = "null";
			if(refClassName.equals(UNKNOWN)) {
				refClassName = "N/A";
				remark  = "'非自行開發'";
			}
			//
			String format = String.format(
					"INSERT INTO BS_SOURCE_RELATION (BS, WAR, FILE_NAME, REF_KEY, REF_FILE_NAME, RELATION_TYPE, REMARK) VALUES ('%s', '%s', '%s', '%s', '%s', 'M', %s);",
					JavaSourceAnalyzer.BS, JavaSourceAnalyzer.WAR, filePath, typeName, refClassName, remark);
			if(!skipSet.contains(format)) {
				sqlStatements.add(format);
			}
		}
	}

	private String findClassFilePath(String qualifiedName) {
		String classFilePath = qualifiedName.replace('.', '/') + JavaSourceAnalyzer.FILE_EXT;
		for (SourceDir srcDir : srcDirs) {
			Path classPath = Paths.get(srcDir.getSrcDir()).resolve(classFilePath);
			if (Files.exists(classPath)) {
				return classPath.toString().replaceAll(Pattern.quote(Initer.ROOT_SRC_DIR) + "2?", "src");
			}
		}
		return UNKNOWN;
	}

	private void increaseExceptonCount() {
		exceptionCount = getExceptionCount() + 1;
	}

	public int getExceptionCount() {
		return exceptionCount;
	}
}