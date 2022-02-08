/*
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.spatial.ogc.csw.catalog.endpoint;

import java.io.OutputStream;
import java.io.StringReader;
import java.io.Writer;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXSource;
import org.codice.ddf.platform.util.XMLUtils;
import org.codice.ddf.spatial.ogc.csw.catalog.endpoint.api.CswQueryFactory;
import org.codice.ddf.spatial.ogc.csw.catalog.endpoint.api.CswXmlBinding;
import org.xml.sax.InputSource;

public class CswXmlBindingImpl implements CswXmlBinding {

  private final JAXBContext jaxBContext;
  private final SAXParserFactory saxParserFactory;

  @SuppressWarnings("unused")
  public CswXmlBindingImpl() throws JAXBException {
    saxParserFactory = XMLUtils.getInstance().getSecureSAXParserFactory();
    saxParserFactory.setNamespaceAware(true);
    jaxBContext =
        JAXBContext.newInstance(
            "net.opengis.cat.csw.v_2_0_2:"
                + "net.opengis.filter.v_1_1_0:net.opengis.gml.v_3_1_1:net.opengis.ows.v_1_0_0",
            CswQueryFactory.class.getClassLoader());
  }

  @Override
  public Unmarshaller createUnmarshaller() throws Exception {
    return jaxBContext.createUnmarshaller();
  }

  @Override
  public Object unmarshal(Source source) throws Exception {
    return createUnmarshaller().unmarshal(source);
  }

  @Override
  public Object unmarshal(StringReader reader) throws Exception {
    Source xmlSource =
        new SAXSource(saxParserFactory.newSAXParser().getXMLReader(), new InputSource(reader));
    return unmarshal(xmlSource);
  }

  Marshaller createMarshaller() throws Exception {
    Marshaller marshaller = jaxBContext.createMarshaller();
    marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);

    return marshaller;
  }

  @Override
  public void marshall(Object obj, Writer w) throws Exception {
    createMarshaller().marshal(obj, w);
  }

  @Override
  public void marshal(Object obj, OutputStream os) throws Exception {
    createMarshaller().marshal(obj, os);
  }
}
