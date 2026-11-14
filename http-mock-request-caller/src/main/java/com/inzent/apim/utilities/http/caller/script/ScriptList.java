package com.inzent.apim.utilities.http.caller.script;

import java.util.AbstractList;
import java.util.List;

/** Read-only indexed JSON list with no host collection implementation exposure. */
public final class ScriptList extends AbstractList<Object> {
    private final List<Object> values;
    ScriptList(List<Object> values) {this.values=values;}
    @Override public Object get(int index) {return values.get(index);}
    @Override public int size() {return values.size();}
}
