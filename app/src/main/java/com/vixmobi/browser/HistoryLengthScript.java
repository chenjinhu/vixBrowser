package com.vixmobi.browser;

/** A document-start override; it never adds, removes or navigates real history entries. */
final class HistoryLengthScript {
    private HistoryLengthScript() {}

    static String create(Integer length) {
        String value = length == null ? "null" : Integer.toString(length);
        return "(function(){try{"
                + "var proto=window.History&&window.History.prototype;if(!proto)return;"
                + "var key=Symbol.for('com.vixmobi.browser.historyLength');"
                + "var saved=Object.prototype.hasOwnProperty.call(proto,key)?proto[key]:null;"
                + "var value=" + value + ";"
                + "if(value===null){if(saved){"
                + "if(saved.descriptor)Object.defineProperty(proto,'length',saved.descriptor);"
                + "else delete proto.length;delete proto[key];}return;}"
                + "if(!saved){saved={descriptor:Object.getOwnPropertyDescriptor(proto,'length')};"
                + "Object.defineProperty(proto,key,{value:saved,configurable:true});}"
                + "Object.defineProperty(proto,'length',{get:function(){return value;},"
                + "enumerable:saved.descriptor?saved.descriptor.enumerable:true,configurable:true});"
                + "}catch(ignored){}})();";
    }
}
