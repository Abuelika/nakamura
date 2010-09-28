package org.sakaiproject.nakamura.http.cache;

import org.apache.sling.api.SlingHttpServletResponse;

import java.io.IOException;
import java.io.Serializable;
import java.util.Locale;

public class OperationResponseReplay implements Serializable {

  /**
   * 
   */
  private static final long serialVersionUID = 3978261366893966679L;
  private Operation[] operations;
  private String stringContent;
  private byte[] byteContent;

  public OperationResponseReplay(Operation[] operations, byte[] byteContent, String stringContent) {
    this.operations = operations;
    this.byteContent = byteContent;
    this.stringContent = stringContent;
  }

  /**
   * Replay the cached request
   * @param sresponse
   * @throws IOException
   */
  @SuppressWarnings("deprecation")
  public void replay(SlingHttpServletResponse sresponse) throws IOException {
    for ( Operation o : operations ) {
      int op = o.getOperation();
      switch (op) {
      case OperationResponseCapture.ADD_DATE_HEADER:
        sresponse.addDateHeader((String)o.get(0),(Long)o.get(1));
        break;
      case OperationResponseCapture.ADD_HEADER:
        sresponse.addHeader((String)o.get(0), (String)o.get(1));
        break;
      case OperationResponseCapture.ADD_INT_HEADER:
        sresponse.addIntHeader((String)o.get(0), (Integer)o.get(1));
        break;
      case OperationResponseCapture.SET_CHARACTER_ENCODING:
        sresponse.setCharacterEncoding((String)o.get(0));
        break;
      case OperationResponseCapture.SET_CONTENT_LENGTH:
        sresponse.setContentLength((Integer)o.get(0));
        break;
      case OperationResponseCapture.SET_CONTENT_TYPE:
        sresponse.setContentType((String)o.get(0));
        break;
      case OperationResponseCapture.SET_DATE_HEADER:
        sresponse.setDateHeader((String)o.get(0),(Long)o.get(1));        
        break;
      case OperationResponseCapture.SET_HEADER:
        sresponse.setHeader((String)o.get(0),(String)o.get(1));
        break;
      case OperationResponseCapture.SET_INT_HEADER:
        sresponse.setIntHeader((String)o.get(0),(Integer)o.get(1));
        break;
      case OperationResponseCapture.SET_LOCALE:
        sresponse.setLocale(new Locale((String)o.get(0),(String)o.get(1)));
        break;
      case OperationResponseCapture.SET_STATUS:
        sresponse.setStatus((Integer)o.get(0));
        break;
      case OperationResponseCapture.SET_STATUS_WITH_MESSAGE:
        sresponse.setStatus((Integer)o.get(0),(String)o.get(1));
        break;
      }
    }

     if ( stringContent != null  ) {
       sresponse.getWriter().write(stringContent);
     } else if ( byteContent != null ){
       sresponse.getOutputStream().write(byteContent);
     }

  }


}
