/**
 *    Copyright 2006-2016 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.generator.internal;

import com.sun.org.apache.xerces.internal.dom.DeferredAttrImpl;
import com.sun.org.apache.xerces.internal.dom.DeferredTextImpl;
import com.sun.org.apache.xerces.internal.dom.TextImpl;
import org.mybatis.generator.api.GeneratedXmlFile;
import org.mybatis.generator.config.MergeConstants;
import org.mybatis.generator.exception.ShellException;
import org.w3c.dom.*;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.mybatis.generator.internal.util.messages.Messages.getString;

/**
 * This class handles the task of merging changes into an existing XML file.
 * 
 * @author Jeff Butler
 */
public class XmlFileMergerJaxp {

    /**
     * id 属性名
     */
    private static final String UNIQUE_KEY_ID = "id";

    private static class NullEntityResolver implements EntityResolver {
        /**
         * returns an empty reader. This is done so that the parser doesn't
         * attempt to read a DTD. We don't need that support for the merge and
         * it can cause problems on systems that aren't Internet connected.
         */
        public InputSource resolveEntity(String publicId, String systemId)
                throws SAXException, IOException {

            StringReader sr = new StringReader(""); //$NON-NLS-1$

            return new InputSource(sr);
        }
    }

    /**
     * Utility class - no instances allowed
     */
    private XmlFileMergerJaxp() {
        super();
    }

    public static String getMergedSource(GeneratedXmlFile generatedXmlFile,
            File existingFile) throws ShellException {

        try {
            return getMergedSource(new InputSource(new StringReader(generatedXmlFile.getFormattedContent())),
                new InputSource(new InputStreamReader(new FileInputStream(existingFile), "UTF-8")), //$NON-NLS-1$
                existingFile.getName());
        } catch (IOException e) {
            throw new ShellException(getString("Warning.13", //$NON-NLS-1$
                    existingFile.getName()), e);
        } catch (SAXException e) {
            throw new ShellException(getString("Warning.13", //$NON-NLS-1$
                    existingFile.getName()), e);
        } catch (ParserConfigurationException e) {
            throw new ShellException(getString("Warning.13", //$NON-NLS-1$
                    existingFile.getName()), e);
        }
    }
    
    public static String getMergedSource(InputSource newFile,
            InputSource existingFile, String existingFileName) throws IOException, SAXException,
            ParserConfigurationException, ShellException {

        DocumentBuilderFactory factory = DocumentBuilderFactory
                .newInstance();
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver(new NullEntityResolver());

        Document existingDocument = builder.parse(existingFile);
        Document newDocument = builder.parse(newFile);

        DocumentType newDocType = newDocument.getDoctype();
        DocumentType existingDocType = existingDocument.getDoctype();

        if (!newDocType.getName().equals(existingDocType.getName())) {
            throw new ShellException(getString("Warning.12", //$NON-NLS-1$
                    existingFileName));
        }

        Element newRootElement = newDocument.getDocumentElement();

        mergeGenerateNodeForExistXml(existingDocument,newRootElement);

        // pretty print the result
        return prettyPrint(existingDocument);
    }

    /**
     * 将整个node节点下面的子节点转换成map结构
     * key -> 子节点的id属性值，因为xml文件的每个select|update|delete|where|sql|insert等等节点都有id属性，且同一个文件中唯一
     * value -> 代表该子节点
     *
     * @param node
     * @return
     */
    private static Map<String,Node> transformElement2Map(Node node){

        Map<String,Node> nodeMap = new HashMap<>();

        // 节点为Null，则直接返回
        if (node == null){

            return nodeMap;
        }

        // 获取该节点下的所有子节点
        NodeList childNodes = node.getChildNodes();

        int length = childNodes.getLength();

        // 遍历子节点
        for (int index = 0; index < length; index++){

            Node item = childNodes.item(index);

            // 因为一个xml文件中，避免不了有空行，可以理解为这也是一个节点，是一个空白节点
            if (isWhiteSpace(item)){

                continue;
            }

            String value = getNodeAttributeValue(item, UNIQUE_KEY_ID);

            if (value != null){

                nodeMap.put(value,item);
            }
        }

        return nodeMap;
    }

    /**
     * 获取某个节点的某个属性值
     *
     * @param node 节点
     * @param attrName 属性名称
     * @return
     */
    private static String getNodeAttributeValue(Node node,String attrName){

        if (node == null || attrName == null || attrName.trim().length() == 0){

            return null;
        }

        if (isWhiteSpace(node)){

            return null;
        }

        // 如果node节点是element类型，则直接获取属性值
        // 在这里，一般情况都是element类型
        if (node instanceof Element){

            Element element = (Element) node;

            return element.getAttribute(attrName);
        }

        // 如果不是的话，则获取该node节点所有属性
        NamedNodeMap attributes = node.getAttributes();

        if (attributes == null){

            // 这里为null，有可能该节点是一个注释
            return null;
        }

        // 这里获取的就是属性节点
        Node namedItem = attributes.getNamedItem(attrName);

        if (namedItem != null){

            return namedItem.getNodeValue();
        }

        return null;
    }

    /**
     * 从旧文件中移除自动生成的node节点，
     * 移除原理：
     * 因为一般情况，从新生成xml，肯定代表着有字段删减或增加等情况，
     * 这时候，移除旧文件中的之前生成的node节点(不包括自定义)，然后把新生成的所有node节点
     * 全部移动过去。
     * 这是是采用的挨个比较的方法，所以不用担心，有自定义代码插件会不会被弄没了，
     * 只要你没改动自动生成代码规则，就没事，
     * 这里需要注意的是，有些会在原自动生成的代码中加入一些属性，
     * 如insert方法，加入主键返回 如，useGeneratedKeys="true" keyColumn="id"，
     * 所以需要考虑到这些新增的属性也要一并移植过去，
     * ===========================================================
     * 但值得注意的是，你改动了sql，这个是这个方法没办法辨别的。。。
     * ===========================================================
     *
     * @param existingDocument
     * @param newRootElement
     */
    private static void mergeGenerateNodeForExistXml(Document existingDocument,Element newRootElement){

        Element existRootElement = existingDocument.getDocumentElement();

        // 移除旧节点(mapper节点)的namespace属性(一般情况都是这个属性)
        NamedNodeMap attributes = existRootElement.getAttributes();
        int attributeCount = attributes.getLength();
        for (int i = attributeCount - 1; i >= 0; i--) {
            Node node = attributes.item(i);
            existRootElement.removeAttribute(node.getNodeName());
        }

        // 新增namespace属性
        attributes = newRootElement.getAttributes();
        attributeCount = attributes.getLength();
        for (int i = 0; i < attributeCount; i++) {
            Node node = attributes.item(i);
            existRootElement.setAttribute(node.getNodeName(), node
                    .getNodeValue());
        }


        // 存放旧文件的所有节点信息
        Map<String, Node> existNodeMap = transformElement2Map(existRootElement);

        // 存放所有待删除的节点信息
        List<Node> waitDeleteNodeList = new ArrayList<>();

        // 获取新生成的所有子节点
        NodeList childNodes = newRootElement.getChildNodes();

        int length = childNodes.getLength();

        // 遍历所有新生成的
        for (int index = 0; index < length; index++){

            Node item = childNodes.item(index);

            // 空白节点，这里不做处理
            if (isWhiteSpace(item)){

                continue;
            }

            // 获取当前遍历节点的id属性值
            String nodeAttributeValue = getNodeAttributeValue(item, UNIQUE_KEY_ID);

            if (nodeAttributeValue == null){

                // 既不是空白节点，又没有id属性值，则直接抛出异常，不做处理
                throw new RuntimeException("【"+item.getNodeName()+"】节点没有id属性");
            }

            Node existNode = existNodeMap.get(nodeAttributeValue);

            if (existNode != null){

                // 说明该节点是自动生成的，而不是自定义的，需要加入到待删除集合中
                waitDeleteNodeList.add(existNode);

                // 需要遍历属性，移植自定义的属性
                NamedNodeMap newAttrMap = item.getAttributes();

                NamedNodeMap oldAttrMap = existNode.getAttributes();

                // 只遍历新增的属性，删除的属性不处理
                int attrLength = oldAttrMap.getLength();

                for (int i = 0; i < attrLength; i++){

                    // 属性节点
                    Node attrNode = oldAttrMap.item(i);

                    Node diffNode = newAttrMap.getNamedItem(attrNode.getNodeName());

                    if (diffNode == null){

                        // 说明，旧节点存在该属性，但新节点不存在，由此判断，该属性节点是自定义新增的
                        // 需要移植
                        if (item instanceof Element){

                            Element element = (Element) item;

                            element.setAttribute(attrNode.getNodeName(),attrNode.getNodeValue());
                        }
                    }
                }
            }
        }

        // 删除节点
        for (Node deleteNode : waitDeleteNodeList){

            existRootElement.removeChild(deleteNode);
        }

        NodeList children = newRootElement.getChildNodes();
        length = children.getLength();
        Node firstChild = existRootElement.getFirstChild();

        for (int i = 0; i < length; i++) {

            Node node = children.item(i);
            // don't add the last node if it is only white space
            if (i == length - 1 && isWhiteSpace(node)) {
                break;
            }

            Node newNode = existingDocument.importNode(node, true);
            if (firstChild == null) {
                existRootElement.appendChild(newNode);
            } else {
                existRootElement.insertBefore(newNode, firstChild);
            }
        }

        // 格式化节点
        formatDocument(existRootElement);
    }

    /**
     * 获取第一个不是空白节点的子节点
     *
     * @param node
     * @return
     */
    private static Node getFirstNodeByNotWhiteNode(Node node){

        if (node == null || isWhiteSpace(node)){

            return null;
        }

        NodeList childNodes = node.getChildNodes();

        int length = childNodes.getLength();

        for (int index = 0; index < length; index++){

            Node item = childNodes.item(index);

            if (!isWhiteSpace(item)){

                return item;
            }
        }

        return null;
    }

    private static void formatDocument(Element existElement){

        // 去除多余的空白节点，并且空白节点的data就换两行\n\n
        NodeList childNodes = existElement.getChildNodes();

        // 待删除的空白节点
        List<Node> waitDeleteNodeList = new ArrayList<>();

        int length = childNodes.getLength();

        for (int index = 0; index < length; index++){

            Node item = childNodes.item(index);

            // 如果当前节点和下一个节点都是空白节点，则删除下一个空白的节点
            if (isWhiteSpace(item) && isWhiteSpace(item.getNextSibling())){

                item.setNodeValue("  \n\n  ");
                waitDeleteNodeList.add(item.getNextSibling());
            }
        }

        for (Node node : waitDeleteNodeList){

            existElement.removeChild(node);
        }
    }

    private static String prettyPrint(Document document) throws ShellException {
        DomWriter dw = new DomWriter();
        String s = dw.toString(document);
        return s;
    }

    private static boolean isGeneratedNode(Node node) {
        boolean rc = false;

        if (node != null && node.getNodeType() == Node.ELEMENT_NODE) {
            Element element = (Element) node;
            String id = element.getAttribute("id"); //$NON-NLS-1$
            if (id != null) {
                for (String prefix : MergeConstants.OLD_XML_ELEMENT_PREFIXES) {
                    if (id.startsWith(prefix)) {
                        rc = true;
                        break;
                    }
                }
            }

            if (rc == false) {
                // check for new node format - if the first non-whitespace node
                // is an XML comment, and the comment includes
                // one of the old element tags,
                // then it is a generated node
                NodeList children = node.getChildNodes();
                int length = children.getLength();
                for (int i = 0; i < length; i++) {
                    Node childNode = children.item(i);
                    if (isWhiteSpace(childNode)) {
                        continue;
                    } else if (childNode.getNodeType() == Node.COMMENT_NODE) {
                        Comment comment = (Comment) childNode;
                        String commentData = comment.getData();
                        for (String tag : MergeConstants.OLD_ELEMENT_TAGS) {
                            if (commentData.contains(tag)) {
                                rc = true;
                                break;
                            }
                        }
                    } else {
                        break;
                    }
                }
            }
        }

        return rc;
    }

    private static boolean isWhiteSpace(Node node) {
        boolean rc = false;

        if (node != null && node.getNodeType() == Node.TEXT_NODE) {
            Text tn = (Text) node;
            if (tn.getData().trim().length() == 0) {
                rc = true;
            }
        }

        return rc;
    }
}
