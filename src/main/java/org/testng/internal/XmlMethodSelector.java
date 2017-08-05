package org.testng.internal;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testng.IMethodSelector;
import org.testng.IMethodSelectorContext;
import org.testng.ITestNGMethod;
import org.testng.collections.ListMultiMap;
import org.testng.collections.Lists;
import org.testng.collections.Maps;
import org.testng.internal.reflect.ReflectionHelper;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlInclude;

/**
 * This class is the default method selector used by TestNG to determine
 * which methods need to be included and excluded based on the specification
 * given in testng.xml.
 *
 * Created on Sep 30, 2005
 */
public class XmlMethodSelector implements IMethodSelector {

  // Groups included and excluded for this run
  private Map<String, String> m_includedGroups = Maps.newHashMap();
  private Map<String, String> m_excludedGroups = Maps.newHashMap();
  private List<XmlClass> m_classes = null;
  // The BeanShell expression for this test, if any
  private String m_expression = null;
  // List of methods included implicitly
  private final ListMultiMap<String, XmlInclude> m_includedMethods = Maps.newListMultiMap();
  private final IBsh m_bsh = Dynamic.hasBsh() ? new Bsh() : new BshMock();

  @Override
  public boolean includeMethod(IMethodSelectorContext context,
      ITestNGMethod tm, boolean isTestMethod)
  {

    if (! m_isInitialized) {
      m_isInitialized = true;
      init(context);
    }

    if (null != m_expression) {
      return m_bsh.includeMethodFromExpression(m_expression, tm);
    }
    return includeMethodFromIncludeExclude(tm, isTestMethod);
  }

  private boolean includeMethodFromIncludeExclude(ITestNGMethod tm, boolean isTestMethod) {
    boolean result = false;
    ConstructorOrMethod method = tm.getConstructorOrMethod();
    String[] groups = tm.getGroups();
    Map<String, String> includedGroups = m_includedGroups;
    Map<String, String> excludedGroups = m_excludedGroups;
    List<XmlInclude> includeList =
        m_includedMethods.get(MethodHelper.calculateMethodCanonicalName(tm));

    //
    // No groups were specified:
    //
    if (includedGroups.size() == 0 && excludedGroups.size() == 0
        && ! hasIncludedMethods() && ! hasExcludedMethods())
    //
    // If we don't include or exclude any methods, method is in
    //
    {
      result = true;
    }
    //
    // If it's a configuration method and no groups were requested, we want it in
    //
    else if (includedGroups.size() == 0 && excludedGroups.size() == 0 && ! isTestMethod)
    {
      result = true;
    }

    //
    // Is this method included implicitly?
    //
    else if (!includeList.isEmpty()) {
      result = true;
    }

    //
    // Include or Exclude groups were specified:
    //
    else {
      //
      // Only add this method if it belongs to an included group and not
      // to an excluded group
      //
      boolean noGroupsSpecified = false; /* Explicitly disable logic to consider size for groups */
      boolean isIncludedInGroups = isIncluded(groups, m_includedGroups.values(), noGroupsSpecified);
      boolean isExcludedInGroups = isExcluded(groups, m_excludedGroups.values());

      //
      // Calculate the run methods by groups first
      //
      if (isIncludedInGroups && !isExcludedInGroups) {
        result = true;
      } else if (isExcludedInGroups) {
        result = false;
      }

      if(isTestMethod) {
        //
        // Now filter by method name
        //
        Class methodClass = method.getDeclaringClass();
        String fullMethodName = methodClass.getName() + "." + method.getName();

        String[] fullyQualifiedMethodName = new String[] { fullMethodName };

        //Check if groups was involved or not. If groups was not involved then we should not be
        // involving the size of the list for evaluation of "isIncluded"
        noGroupsSpecified = (m_includedGroups.isEmpty() && m_excludedGroups.isEmpty());

        //
        // Iterate through all the classes so we can gather all the included and
        // excluded methods
        //
        for (XmlClass xmlClass : m_classes) {
          // Only consider included/excluded methods that belong to the same class
          // we are looking at
          Class cls = xmlClass.getSupportClass();
          if (!assignable(methodClass, cls)) {
            continue;
          }

          List<String> includedMethods = createQualifiedMethodNames(xmlClass, toStringList(xmlClass.getIncludedMethods()));
          boolean isIncludedInMethods = isIncluded(fullyQualifiedMethodName, includedMethods, noGroupsSpecified);
          List<String> excludedMethods = createQualifiedMethodNames(xmlClass, xmlClass.getExcludedMethods());
          boolean isExcludedInMethods = isExcluded(fullyQualifiedMethodName, excludedMethods);
          if (result) {
            // If we're about to include this method by group, make sure
            // it's included by method and not excluded by method
            if (!xmlClass.getIncludedMethods().isEmpty()) {
              result = isIncludedInMethods;
            }
            if (!xmlClass.getExcludedMethods().isEmpty()) {
              result = result && !isExcludedInMethods;
            }
          }
          // otherwise it's already excluded and nothing will bring it back,
          // since exclusions preempt inclusions
        }
      }
    }

    Package pkg = method.getDeclaringClass().getPackage();
    String methodName = pkg != null ? pkg.getName() + "." + method.getName() : method.getName();

    logInclusion(result ? "Including" : "Excluding", "method", methodName + "()");

    return result;
  }

  @SuppressWarnings({"unchecked"})
  private boolean assignable(Class sourceClass, Class targetClass) {
    return sourceClass.isAssignableFrom(targetClass) || targetClass.isAssignableFrom(sourceClass);
  }

  private Map<String, String> m_logged = Maps.newHashMap();
  private void logInclusion(String including, String type, String name) {
    if (! m_logged.containsKey(name)) {
      log(4, including + " " + type + " " + name);
      m_logged.put(name, name);
    }
  }

  private boolean hasIncludedMethods() {
    for (XmlClass xmlClass : m_classes) {
      if (xmlClass.getIncludedMethods().size() > 0) {
        return true;
      }
    }

    return false;
  }

  private boolean hasExcludedMethods() {
    for (XmlClass xmlClass : m_classes) {
      if (xmlClass.getExcludedMethods().size() > 0) {
        return true;
      }
    }

    return false;
  }

  private List<String> toStringList(List<XmlInclude> methods) {
    List<String> result = Lists.newArrayList();
    for (XmlInclude m : methods) {
      result.add(m.getName());
    }
    return result;
  }

  private static List<String> createQualifiedMethodNames(XmlClass xmlClass,
      List<String> methods) {
    List<String> vResult = Lists.newArrayList();
    Class<?> cls = xmlClass.getSupportClass();

    while (null != cls) {
      for (String im : methods) {
        Pattern pattern = Pattern.compile(methodName(im));
        Method[] allMethods = ReflectionHelper.getLocalMethods(cls);
        for (Method m : allMethods) {
          if (pattern.matcher(m.getName()).matches()) {
            vResult.add(makeMethodName(m.getDeclaringClass().getName(), m.getName()));
          }
        }
      }
      cls = cls.getSuperclass();
    }

    return vResult;
  }

  private static final String QUOTED_DOLLAR = Matcher.quoteReplacement("\\$");
  private static String methodName(String methodName) {
    if (methodName.contains("\\$")) {
      return methodName;
    }
    return methodName.replaceAll("\\Q$\\E", QUOTED_DOLLAR);
  }

  private static String makeMethodName(String className, String methodName) {
    return className + "." + methodName;
  }

  private void checkMethod(Class<?> c, String methodName) {
    Pattern p = Pattern.compile(methodName);
    for (Method m : c.getMethods()) {
      if (p.matcher(m.getName()).matches()) {
        return;
      }
    }
    Utils.log("Warning", 2, "The regular expression \"" + methodName + "\" didn't match any" +
              " method in class " + c.getName());
  }

  public void setXmlClasses(List<XmlClass> classes) {
    m_classes = classes;
    for (XmlClass c : classes) {
      for (XmlInclude m : c.getIncludedMethods()) {
        checkMethod(c.getSupportClass(), m.getName());
        String methodName = makeMethodName(c.getName(), m.getName());
        m_includedMethods.put(methodName, m);
      }
    }
  }

  /**
   * @return Returns the excludedGroups.
   */
  public Map<String, String> getExcludedGroups() {
    return m_excludedGroups;
  }

  /**
   * @return Returns the includedGroups.
   */
  public Map<String, String> getIncludedGroups() {
    return m_includedGroups;
  }

  /**
   * @param excludedGroups The excludedGroups to set.
   */
  public void setExcludedGroups(Map<String, String> excludedGroups) {
    m_excludedGroups = excludedGroups;
  }

  /**
   * @param includedGroups The includedGroups to set.
   */
  public void setIncludedGroups(Map<String, String> includedGroups) {
    m_includedGroups = includedGroups;
  }

  private static boolean isIncluded(String[] groups, Collection<String> includedGroups, boolean noGroupsSpecified) {
    if (noGroupsSpecified) {
      return isMemberOf(groups, includedGroups);
    }
    return (includedGroups.isEmpty() || isMemberOf(groups, includedGroups));
  }

  private static boolean isExcluded(String[] groups, Collection<String> excludedGroups) {
    return isMemberOf(groups, excludedGroups);
  }

  /**
   *
   * @param groups Array of groups on the method
   * @param list Map of regexps of groups to be run
   */
  private static boolean isMemberOf(String[] groups, Collection<String> list) {
    for (String group : groups) {
      for (String o : list) {
        String regexpStr = methodName(o);
        if (Pattern.matches(regexpStr, group)) {
          return true;
        }
      }
    }

    return false;
  }

  private static void log(int level, String s) {
    Utils.log("XmlMethodSelector", level, s);
  }

  public void setExpression(String expression) {
    m_expression = expression;
  }

  private boolean m_isInitialized = false;
  private List<ITestNGMethod> m_testMethods = null;

  @Override
  public void setTestMethods(List<ITestNGMethod> testMethods) {
    // Caution: this variable is initialized with an empty list first and then modified
    // externally by the caller (TestRunner#fixMethodWithClass). Ugly.
    m_testMethods = testMethods;
  }

  private void init(IMethodSelectorContext context) {
    String[] groups = m_includedGroups.keySet().toArray(new String[m_includedGroups.size()]);
    Set<String> groupClosure = new HashSet<>();
    Set<ITestNGMethod> methodClosure = new HashSet<>();

    List<ITestNGMethod> includedMethods = Lists.newArrayList();
    for (ITestNGMethod m : m_testMethods) {
      if (includeMethod(context, m, true)) {
        includedMethods.add(m);
      }
    }
    MethodGroupsHelper.findGroupTransitiveClosure(this, includedMethods, m_testMethods,
        groups, groupClosure, methodClosure);

    // If we are asked to include or exclude specific groups, calculate
    // the transitive closure of all the included groups.  If no include groups
    // were specified, don't do anything.
    // Any group that is part of the transitive closure but not part of
    // m_includedGroups is being added implicitly by TestNG so that if someone
    // includes a group z that depends on a, b and c, they don't need to
    // include a, b and c explicitly.
    if (m_includedGroups.size() > 0) {
      // Make the transitive closure our new included groups
      for (String g : groupClosure) {
        log(4, "Including group "
            + (m_includedGroups.containsKey(g) ?
                ": " : "(implicitly): ") + g);
        m_includedGroups.put(g, g);
      }

      // Make the transitive closure our new included methods
      for (ITestNGMethod m : methodClosure) {
        String methodName = m.getQualifiedName();
        XmlInclude xi = new XmlInclude(methodName);
        // TODO: set the XmlClass on this xi or we won't get inheritance of parameters
        m_includedMethods.put(methodName, xi);
        logInclusion("Including", "method ", methodName);
      }
    }
  }
}
