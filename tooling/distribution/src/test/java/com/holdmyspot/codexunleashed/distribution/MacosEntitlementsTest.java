package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.testng.annotations.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** Checks the maintained XML entitlement files used by macOS signing. */
public final class MacosEntitlementsTest
{
	private static final String ALLOW_JIT = "com.apple.security.cs.allow-jit";
	private static final String ALLOW_UNSIGNED_MEMORY = "com.apple.security.cs.allow-unsigned-executable-memory";

	/** Creates signing policy tests. */
	public MacosEntitlementsTest()
	{
	}

	/**
	 * Requires both V8 memory permissions on each executable that embeds V8.
	 *
	 * @throws IOException if a maintained entitlement file cannot be read
	 * @throws ParserConfigurationException if the JDK XML parser cannot be constructed
	 * @throws SAXException if an entitlement document is malformed
	 */
	@Test
	public void checksV8ExecutableEntitlements() throws IOException, ParserConfigurationException, SAXException
	{
		for (String binary : List.of("codex", "codex-app-server", "codex-code-mode-host"))
			assertEquals(readEntitlements(binary), Map.of(ALLOW_JIT, true, ALLOW_UNSIGNED_MEMORY, true), binary);
	}

	/**
	 * Requires the responses proxy's single retained JIT permission.
	 *
	 * @throws IOException if the entitlement file cannot be read
	 * @throws ParserConfigurationException if the JDK XML parser cannot be constructed
	 * @throws SAXException if the document is malformed
	 */
	@Test
	public void checksResponsesProxyEntitlements() throws IOException, ParserConfigurationException, SAXException
	{
		assertEquals(readEntitlements("codex-responses-api-proxy"), Map.of(ALLOW_JIT, true));
	}

	/**
	 * Reads the maintained XML plist dictionary without fetching its external Apple DTD.
	 *
	 * @param binary executable basename
	 * @return declared boolean entitlements
	 * @throws IOException if the maintained file cannot be read
	 * @throws ParserConfigurationException if the JDK XML parser cannot be constructed
	 * @throws SAXException if the document is malformed
	 */
	private static Map<String, Boolean> readEntitlements(String binary)
		throws IOException, ParserConfigurationException, SAXException
	{
		Path workflow = Path.of(System.getProperty("tooling.release.workflow"));
		Path file = workflow.getParent().getParent().resolve("scripts/macos-signing/" + binary + ".entitlements.plist");
		DocumentBuilder parser = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder();
		parser.setEntityResolver((_, _) -> new InputSource(new StringReader("")));
		Element plist = parser.parse(file.toFile()).getDocumentElement();
		assertEquals(plist.getTagName(), "plist");
		NodeList dictionaries = plist.getElementsByTagName("dict");
		assertEquals(dictionaries.getLength(), 1, file.toString());
		NodeList children = dictionaries.item(0).getChildNodes();
		Map<String, Boolean> entitlements = new LinkedHashMap<>();
		String key = null;
		for (int index = 0; index < children.getLength(); index += 1)
		{
			Node child = children.item(index);
			if (!(child instanceof Element element))
				continue;
			String tag = element.getTagName();
			if (tag.equals("key"))
			{
				assertNull(key, file.toString());
				key = element.getTextContent();
			}
			else
			{
				assertNotNull(key, file.toString());
				assertTrue(List.of("true", "false").contains(tag), file.toString());
				entitlements.put(key, tag.equals("true"));
				key = null;
			}
		}
		assertNull(key, file.toString());
		return entitlements;
	}
}
