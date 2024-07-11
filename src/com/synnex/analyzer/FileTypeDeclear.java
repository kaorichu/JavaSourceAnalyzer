package com.synnex.analyzer;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;

public class FileTypeDeclear {
	private final JavaParserFacade javaParserFacade;
	private Map<String, FileType> targetList  = new HashMap<>();
	private Map<String, FileType> packageList = Map.of(".*action.*", FileType.MODEL);
	public FileTypeDeclear(TypeSolver typeSolver) {
		this.javaParserFacade = JavaParserFacade.get(typeSolver);
		targetList.put("javax.servlet.http.HttpServlet", FileType.M1);
		targetList.put("com.synnex.erp.report.impl.BaseGenPDFReport", FileType.ACTION);
		targetList.put("com.synnex.erp.report.BaseGenEXCELReport", FileType.ACTION);
		targetList.put("org.apache.struts.actions.DispatchAction", FileType.ACTION);
		targetList.put("com.synnex.erp.core.bd.BaseBD", FileType.BD);
		targetList.put("com.synnex.erp.core.service.BaseService", FileType.SERVICE);
		targetList.put("com.synnex.erp.core.dao.BaseDAO", FileType.DAO);
		targetList.put("javax.servlet.Filter", FileType.FILTER);
		targetList.put("javax.servlet.ServletContextListener", FileType.LISTENER);
	}

	public FileType findType(ClassOrInterfaceDeclaration c) {
		FileType result = findByExtends(c);
		if(result != FileType.UNKNOW) {
			return result;
		}
		Optional<PackageDeclaration> pd = c.getParentNode().filter(CompilationUnit.class::isInstance)
				.map(CompilationUnit.class::cast).flatMap(cu -> cu.getPackageDeclaration());
		if(pd.isPresent()) {
			result = findByPackageName(pd.get());
		}
		if(result != FileType.UNKNOW) {
			return result;
		}
		result = findByName(
				pd.map(PackageDeclaration::getNameAsString).filter(String::isEmpty).map(p -> p + ".").orElse("")
						+ c.getNameAsString());
		return result;
	}
	public FileType findByName(String name) {
		return Stream.of(FileType.values()).filter(e -> name.contains(e.name())).findFirst().orElse(FileType.UNKNOW);
	}

	public FileType findByPackageName(PackageDeclaration pd) {
		return packageList.entrySet().stream().filter(e -> Pattern.matches(e.getKey(), pd.getNameAsString()))
				.findFirst().map(Entry::getValue).orElse(FileType.UNKNOW);
	}

	public FileType findByExtends(ClassOrInterfaceDeclaration c) {
		var extendList = javaParserFacade.getTypeDeclaration(c).getAllAncestors();
		for(var type : extendList) {
			FileType fileType = targetList.get(type.getQualifiedName());
			if(fileType != null) return fileType;
		}
		return FileType.UNKNOW;
	}
}
