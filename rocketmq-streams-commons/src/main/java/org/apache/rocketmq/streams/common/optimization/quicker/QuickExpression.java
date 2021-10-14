/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.streams.common.optimization.quicker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.rocketmq.streams.common.context.AbstractContext;
import org.apache.rocketmq.streams.common.context.IMessage;

public class QuickExpression {
    protected String expression;
    protected boolean isRegex;
    protected boolean isCaseInsensitive=true;
    protected String varName;
    protected Integer filterIndex;//the expression's index in filter stages
    protected Integer scriptIndex;//the expression's index in script stages
    protected List<?> scripts=new ArrayList<>();
    public QuickExpression(String varName,String expression){
        this.expression=expression;
        this.isRegex=true;
        this.isCaseInsensitive=true;
        this.varName=varName;
    }

    public QuickExpression(String varName,String expression, boolean isRegex){
        this.expression=expression;
        this.isRegex=isRegex;
        if(!this.isRegex){
            isCaseInsensitive=false;
        }
        this.varName=varName;
    }

    public QuickExpression(String varName,String expression, boolean isRegex,boolean isCaseInsensitive){
        this.expression=expression;
        this.isRegex=isRegex;
        this.isCaseInsensitive=isCaseInsensitive;
        this.varName=varName;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public boolean isRegex() {
        return isRegex;
    }

    public String getVarName() {
        return varName;
    }

    @Override public boolean equals(Object o) {
        if (this == o){
            return true;
        }

        if (o == null || getClass() != o.getClass()){
            return false;
        }
        QuickExpression that = (QuickExpression) o;
        return Objects.equals(expression, that.expression) &&
            Objects.equals(varName, that.varName);
    }

    @Override public int hashCode() {
        return Objects.hash(expression, varName);
    }

    public void setVarName(String varName) {
        this.varName = varName;
    }

    public Integer getFilterIndex() {
        return filterIndex;
    }

    public void setFilterIndex(Integer filterIndex) {
        this.filterIndex = filterIndex;
    }

    public Integer getScriptIndex() {
        return scriptIndex;
    }

    public void setScriptIndex(Integer scriptIndex) {
        this.scriptIndex = scriptIndex;
    }

    public List<?> getScripts() {
        return scripts;
    }

    public void setScripts(List<?> scripts) {
        this.scripts = scripts;
    }

    public void setRegex(boolean regex) {
        isRegex = regex;
    }

    public boolean isCaseInsensitive() {
        return isCaseInsensitive;
    }

    public void setCaseInsensitive(boolean caseInsensitive) {
        isCaseInsensitive = caseInsensitive;
    }
}
