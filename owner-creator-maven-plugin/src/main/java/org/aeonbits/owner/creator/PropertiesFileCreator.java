/*
 * Copyright (c) 2012-2015, Luigi R. Viggiano
 * All rights reserved.
 *
 * This software is distributable under the BSD license.
 * See the terms of the BSD license in the documentation provided with this software.
 */

package org.aeonbits.owner.creator;

import java.io.IOException;
import org.aeonbits.owner.Config.DefaultValue;
import org.aeonbits.owner.Config.Key;

import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import static java.lang.String.format;
import org.aeonbits.owner.plugin.annotations.Description;
import org.aeonbits.owner.plugin.annotations.Group;
import org.aeonbits.owner.plugin.annotations.GroupOrder;
import org.aeonbits.owner.plugin.annotations.NoProperty;
import org.aeonbits.owner.plugin.annotations.ValorizedAs;

/**
 * PropertiesFileCreator helps you to automate the process of properties creation.
 * 
 * @author Luca Taddeo
 */
public class PropertiesFileCreator {
    private String templateString = "${header}\n${body}\n${footer}";
    public String header = "# Properties file created for: '%s' \n\n";
    public String footer = "\n# Properties file autogenerated by OWNER :: PropertyFileCreator\n"
            + "# Created [%s] in %s ms\n";

    public long lastExecutionTime = 0;
    
    /**
     * Method to parse the class and write file in the choosen output.
     *
     * @param clazz class to parse
     * @param output output file path
     * @param headerName
     * @param projectName
     *
     */
    public void parse(Class clazz, Writer output, String projectName) throws Exception {
        long startTime = System.currentTimeMillis();
        Header[] groups = parseMethods(clazz);
        long finishTime = System.currentTimeMillis();

        lastExecutionTime = finishTime - startTime;
        
        String result = toPropertiesString(groups, projectName);

        writeProperties(output, result);
    }

    public void changeTemplate(String newTemplate) {
        templateString = newTemplate;
    }
    
    /**
     * Method to get group array with subgroups and properties.
     *
     * @param clazz class to parse
     * @return array of groups
     */
    private Header[] parseMethods(Class clazz) {
        List<Header> groups = new ArrayList();
        Header unknownGroup = new Header();
        unknownGroup.title = "GENERIC PROPERTIES";

        for (Method method : clazz.getMethods()) {
            Property prop = new Property();

            prop.inPropertyFile = !method.isAnnotationPresent(NoProperty.class);
            prop.deprecated = method.isAnnotationPresent(Deprecated.class);

            if (method.isAnnotationPresent(Key.class)) {
                Key val = method.getAnnotation(Key.class);
                prop.name = val.value();
            } else {
                prop.name = method.getName();
            }

            // We might replace description annotation with javadoc comment
            // but a .java file is required, in .class file javadoc you can't find it.
            // We should think to an alternative.
            if (method.isAnnotationPresent(Description.class)) {
                Description annotation = method.getAnnotation(Description.class);
                prop.description = annotation.value();
            }

            if (method.isAnnotationPresent(DefaultValue.class)) {
                DefaultValue val = method.getAnnotation(DefaultValue.class);
                prop.defaultValue = val.value();
            }
            
            if (method.isAnnotationPresent(ValorizedAs.class)) {
                ValorizedAs annotation = method.getAnnotation(ValorizedAs.class);
                prop.valorizedAs = annotation.value();
            }

            if (method.isAnnotationPresent(Group.class)) {
                Group annotation = method.getAnnotation(Group.class);
                String[] value = annotation.value();
                Header head = getOrAddHeader(value, groups);
                head.properties.add(prop);
            } else if (prop.inPropertyFile) {
                unknownGroup.properties.add(prop);
            }
        }

        if (!unknownGroup.properties.isEmpty()) {
            groups.add(unknownGroup);
        }
        
        return orderGroup(clazz, groups);
    }

    /**
     * Return header if present in the headers list otherwise add it to the list.
     *
     * @param headersString
     * @param headers
     * @return
     */
    private Header getOrAddHeader(String[] headersString, List<Header> headers) {
        Header header = null;

        List<Header> currentDeep = headers;

        // Go through headers to add
        for (int i = 0; i < headersString.length; i++) {
            Header appHeader = null;
            // Verify if at the current deep level there is an header with the same name
            for (Header head : currentDeep) {
                if (head.title.equals(headersString[i])) {
                    appHeader = head;
                    break;
                }
            }

            // If no header was found, new one will be added
            if (appHeader == null) {
                appHeader = new Header();
                appHeader.title = headersString[i];
                appHeader.deepLevel = i;
                currentDeep.add(appHeader);
            }

            // Save deep headers level reached
            currentDeep = appHeader.subHeaders;
            header = appHeader;
        }

        return header;
    }

    /**
     * Order groups based on passed order.
     *
     * @param groups groups to order
     * @param groupsOrder order to follow
     * @return ordered groups
     */
    private Header[] orderGroup(Class clazz, List<Header> headers) {
        LinkedList<Header> toReturn = new LinkedList();

        // Order groups list if class has grouping annotation otherwise give groups as is
        if (clazz.isAnnotationPresent(GroupOrder.class)) {
            GroupOrder ord = (GroupOrder) clazz.getAnnotation(GroupOrder.class);

            // Order headers in headers array
            for (String groupOrd : ord.value()) {
                Header finded = null;
                for (Header head : headers) {
                    if (head.title.equals(groupOrd)) {
                        finded = head;
                        break;
                    }
                }

                if (finded != null) {
                    toReturn.add(finded);
                }
            }

            // Add to the end the headers not in headers array to order
            for (Header head : headers) {
                Header finded = null;
                for (String groupOrd : ord.value()) {
                    if (head.title.equals(groupOrd)) {
                        finded = head;
                    }
                }

                if (finded == null) {
                    toReturn.add(head);
                }
            }
        } else {
            toReturn = new LinkedList<Header>(headers);
        }
        return toReturn.toArray(new Header[toReturn.size()]);
    }

    /**
     * Convert groups list into string.
     */
    private String toPropertiesString(Header[] groups, String projectName) {
        String headerString = format(header, projectName);
        String bodyString = "";
        String footerString = "";
        
        for (Header group : groups) {
            bodyString += group.toString();
        }

        footerString = generateFileFooter();
        
        return templateString.replace("${header}", headerString)
                .replace("${body}", bodyString)
                .replace("${footer}", footerString);
    }

    private String generateFileFooter() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
	Date date = new Date();
        String dateString = dateFormat.format(date);
        return format(footer, dateString, lastExecutionTime);
    }
    
    private void writeProperties(Writer output, String propertiesString) throws IOException {
        output.write(propertiesString);
    }

}

class Header {

    public String title = "";
    public List<Property> properties = new ArrayList();
    public LinkedList<Header> subHeaders;
    public int deepLevel;

    public Header() {
        properties = new LinkedList<Property>();
        subHeaders = new LinkedList<Header>();
    }
    
    public String toString(boolean subHeader) {
        String header = "";
        String value = "";

        if (subHeader) {
            header = "# ----------------------------\n"
                    + "# - " + title + " -\n"
                    + "# ----------------------------\n";
        } else {
            header = "#------------------------------------------------------------------------------\n"
                    + "# " + title + "\n"
                    + "#------------------------------------------------------------------------------\n";
        }

        for (Property prop : properties) {
            if (prop.inPropertyFile) {
                value += prop.toString() + "\n";
            }
        }

        for (Header head : subHeaders) {
            value += head.toString() + "\n";
        }

        return header + "\n" + value;
    }

    @Override
    public String toString() {
        return toString(deepLevel > 0);
    }
}

class Property {

    public String name = "";
    public String[] comments = new String[0];
    public String description = "";
    public String defaultValue = "";
    public String valorizedAs = "";
    public boolean deprecated = false;
    public boolean inPropertyFile = true;

    @Override
    public String toString() {
        String header = "";
        header = "#\n";

        if (deprecated) {
            header += "# DEPRECATED PROPERTY\n";
        }

        String[] commentLines = description.split("\n");
        for (String comment : commentLines) {
            header += "# " + comment + "\n";
        }

        header += "# \n"
                + "# " + "Default (\"" + getEscapedValue(defaultValue) + "\")\n"
                + "#\n";

        if (valorizedAs == null || valorizedAs.isEmpty()) {
            header += "#" + name + "=" + getEscapedValue(defaultValue) + "\n";
        } else {
            header += name + "=" + getEscapedValue(valorizedAs) + "\n";
        }
        return header;
    }
    
    public String getEscapedValue(String value) {
        return value != null ? value.replace("\\", "\\\\") : "";
    }
}