package org.testng.xml.dom;

import org.testng.collections.Lists;
import org.testng.collections.Maps;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

public class DomUtil {

  private XPath m_xpath;
  private Document m_document;

  public DomUtil(Document doc) {
    XPathFactory xpathFactory = XPathFactory.newInstance();
    m_xpath = xpathFactory.newXPath();
    m_document = doc;
  }

  public void populate(XmlSuite xmlSuite) throws XPathExpressionException {
    NodeList nodes = m_document.getChildNodes();
    Map<String, String> parameters = Maps.newHashMap();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node item1 = nodes.item(i);
      if ("suite".equals(item1.getNodeName()) && item1.getAttributes() != null) {
        populateAttributes(item1, xmlSuite);
        NodeList item1Children = item1.getChildNodes();
        for (int j = 0; j < item1Children.getLength(); j++) {
          Node item2 = item1Children.item(j);
          if ("parameter".equals(item2.getNodeName())) {
            Element e = (Element) item2;
            parameters.put(e.getAttribute("name"), e.getAttribute("value"));
          } else if ("test".equals(item2.getNodeName())) {
            Map<String, String> testParameters = Maps.newHashMap();
            XmlTest xmlTest = new XmlTest(xmlSuite);
            populateAttributes(item2, xmlTest);
            NodeList item2Children = item2.getChildNodes();
            for (int k = 0; k < item2Children.getLength(); k++) {
              Node item3 = item2Children.item(k);
              if ("parameter".equals(item3.getNodeName())) {
                Element e = (Element) item3;
                testParameters.put(e.getAttribute("name"), e.getAttribute("value"));
              } else if ("classes".equals(item3.getNodeName())) {
                NodeList item3Children = item3.getChildNodes();
                for (int l = 0; l < item3Children.getLength(); l++) {
                  Node item4 = item3Children.item(l);
                  if ("class".equals(item4.getNodeName())) {
                    XmlClass xmlClass = new XmlClass();
                    populateAttributes(item4, xmlClass);
                    xmlTest.getClasses().add(xmlClass);
                  }
                }
              } else if ("groups".equals(item3.getNodeName())) {
                //@@
              }
            }

            xmlTest.setParameters(testParameters);
          } else if ("suite-files".equals(item2.getNodeName())) {
            NodeList item2Children = item2.getChildNodes();
            List<String> suiteFiles = Lists.newArrayList();
            for (int k = 0; k < item2Children.getLength(); k++) {
              Node item3 = item2Children.item(k);
              if (item3 instanceof Element) {
                Element e = (Element) item3;
                if ("suite-file".equals(item3.getNodeName())) {
                  suiteFiles.add(e.getAttribute("path"));
                }
              }
            }
            xmlSuite.setSuiteFiles(suiteFiles);
          }
        }
      }
    }

    xmlSuite.setParameters(parameters);
//    XPathExpression expr = m_xpath.compile("//suite/test");
//    NodeList tests = (NodeList) expr.evaluate(m_document, XPathConstants.NODESET);
//    for (int i = 0; i < tests.getLength(); i++) {
//      Node node = tests.item(i);
//      System.out.println("<test>:" + node);
//    }
  }

  private void populateAttributes(Node node, Object object) throws XPathExpressionException {
    for (int j = 0; j < node.getAttributes().getLength(); j++) {
      Node item = node.getAttributes().item(j);
      p(node.getAttributes().item(j).toString());
      setProperty(object, item.getLocalName(), item.getNodeValue());
    }
  }

  private void setProperty(Object object, String name, Object value) {
    String methodName = toCamelCaseSetter(name);
    Method foundMethod = null;
    for (Method m : object.getClass().getDeclaredMethods()) {
      if (m.getName().equals(methodName)) {
        foundMethod = m;
        break;
      }
    }

    if (foundMethod == null) {
      p("Warning: couldn't find setter method " + methodName);
    } else {
      try {
        p("Invoking " + methodName + " with " + value);
        Class<?> type = foundMethod.getParameterTypes()[0];
        if (type == Boolean.class || type == boolean.class) {
          foundMethod.invoke(object, Boolean.parseBoolean(value.toString()));
        } else if (type == Integer.class || type == int.class) {
          foundMethod.invoke(object, Integer.parseInt(value.toString()));
        } else {
          foundMethod.invoke(object, value.toString());
        }
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
      } catch (IllegalAccessException e) {
        e.printStackTrace();
      } catch (InvocationTargetException e) {
        e.printStackTrace();
      }
    }
  }

  private void p(String string) {
//    System.out.println("[XPathUtil] " + string);
  }

  private String toCamelCaseSetter(String name) {
    StringBuilder result = new StringBuilder("set" + name.substring(0, 1).toUpperCase());
    for (int i = 1; i < name.length(); i++) {
      if (name.charAt(i) == '-') {
        result.append(Character.toUpperCase(name.charAt(i + 1)));
        i++;
      } else {
        result.append(name.charAt(i));
      }
    }
    return result.toString();
  }
}