package dev.mikoto2000.rei.feed;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

@Component
public class OpmlParser {
  private static final int MAX_ELEMENT_DEPTH = 128;

  public List<OpmlSubscription> parse(InputStream input) {
    try {
      var factory = DocumentBuilderFactory.newDefaultInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setAttribute("jdk.xml.maxElementDepth", MAX_ELEMENT_DEPTH);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler() {
        @Override
        public void error(SAXParseException exception) throws SAXException { throw exception; }
        @Override
        public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
      });
      Element root = builder.parse(input).getDocumentElement();
      if (!"opml".equals(root.getTagName())) {
        throw new OpmlImportException("not an OPML document");
      }
      Element body = null;
      for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node instanceof Element element && "body".equals(element.getTagName())) {
          body = element;
          break;
        }
      }
      if (body == null) throw new OpmlImportException("OPML body is missing");
      List<OpmlSubscription> subscriptions = new ArrayList<>();
      collect(body, List.of(), subscriptions);
      if (subscriptions.isEmpty()) throw new OpmlImportException("OPML contains no feeds");
      return List.copyOf(subscriptions);
    } catch (SAXException e) {
      throw new OpmlImportException("invalid or unsafe OPML XML", e);
    } catch (IOException e) {
      throw new OpmlImportException("could not read OPML", e);
    } catch (ParserConfigurationException e) {
      throw new OpmlImportException("secure XML parser is unavailable", e);
    }
  }

  private void collect(Element parent, List<String> categories, List<OpmlSubscription> subscriptions) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element outline) || !"outline".equals(outline.getTagName())) continue;
      var childCategories = new ArrayList<>(categories);
      if (outline.hasAttribute("xmlUrl")) {
        subscriptions.add(new OpmlSubscription(outline.getAttribute("title"), outline.getAttribute("text"),
            outline.getAttribute("xmlUrl"), outline.getAttribute("htmlUrl"), categories));
      } else {
        String category = outline.getAttribute("text");
        if (category.isBlank()) category = outline.getAttribute("title");
        if (!category.isBlank()) childCategories.add(category);
      }
      collect(outline, childCategories, subscriptions);
    }
  }
}
