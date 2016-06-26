/*
 * Copyright (c) 2013-2016 Cinchapi Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cinchapi.concourse.lang;

import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;
import java.util.Set;

import com.google.common.collect.Multimap;
import org.apache.commons.lang.StringUtils;

import com.cinchapi.concourse.lang.ConjunctionSymbol;
import com.cinchapi.concourse.lang.KeySymbol;
import com.cinchapi.concourse.lang.OperatorSymbol;
import com.cinchapi.concourse.lang.ParenthesisSymbol;
import com.cinchapi.concourse.lang.PostfixNotationSymbol;
import com.cinchapi.concourse.lang.Symbol;
import com.cinchapi.concourse.lang.TimestampSymbol;
import com.cinchapi.concourse.lang.ValueSymbol;
import com.cinchapi.concourse.lang.ast.AST;
import com.cinchapi.concourse.lang.ast.AndTree;
import com.cinchapi.concourse.lang.ast.ExpressionTree;
import com.cinchapi.concourse.lang.ast.OrTree;
import com.cinchapi.concourse.thrift.Operator;
import com.cinchapi.concourse.util.QuoteAwareStringSplitter;
import com.cinchapi.concourse.util.SplitOption;
import com.cinchapi.concourse.util.StringSplitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * The {@link Parser} is a tool that operates on various aspects of the
 * language.
 * 
 * @author Jeff Nelson
 */
public final class Parser {

    /**
     * Convert a valid and well-formed list of {@link Symbol} objects into a
     * an {@link AST}.
     * <p>
     * NOTE: This method will group non-conjunctive symbols into
     * {@link Expression} objects.
     * </p>
     * 
     * @param symbols
     * @return the symbols in an AST
     */
    public static AST toAbstractSyntaxTree(List<Symbol> symbols) {
        Deque<Symbol> operatorStack = new ArrayDeque<Symbol>();
        Deque<AST> operandStack = new ArrayDeque<AST>();
        symbols = groupExpressions(symbols);
        main: for (Symbol symbol : symbols) {
            if(symbol == ParenthesisSymbol.LEFT) {
                operatorStack.push(symbol);
            }
            else if(symbol == ParenthesisSymbol.RIGHT) {
                while (!operatorStack.isEmpty()) {
                    Symbol popped = operatorStack.pop();
                    if(popped == ParenthesisSymbol.LEFT) {
                        continue main;
                    }
                    else {
                        addASTNode(operandStack, popped);
                    }
                }
                throw new SyntaxException(MessageFormat.format(
                        "Syntax error in {0}: Mismatched parenthesis", symbols));
            }
            else if(symbol instanceof Expression) {
                operandStack.add(ExpressionTree.create((Expression) symbol));
            }
            else {
                operatorStack.push(symbol);
            }
        }
        while (!operatorStack.isEmpty()) {
            addASTNode(operandStack, operatorStack.pop());
        }
        return operandStack.pop();
    }

    /**
     * Convert a valid and well-formed list of {@link Symbol} objects into a
     * Queue in postfix notation.
     * <p>
     * NOTE: This method will group non-conjunctive symbols into
     * {@link Expression} objects.
     * </p>
     * 
     * @param symbols
     * @return the symbols in postfix notation
     */
    public static Queue<PostfixNotationSymbol> toPostfixNotation(
            List<Symbol> symbols) {
        Deque<Symbol> stack = new ArrayDeque<Symbol>();
        Queue<PostfixNotationSymbol> queue = new LinkedList<PostfixNotationSymbol>();
        symbols = groupExpressions(symbols);
        for (Symbol symbol : symbols) {
            if(symbol instanceof ConjunctionSymbol) {
                while (!stack.isEmpty()) {
                    Symbol top = stack.peek();
                    if(symbol == ConjunctionSymbol.OR
                            && (top == ConjunctionSymbol.OR || top == ConjunctionSymbol.AND)) {
                        queue.add((PostfixNotationSymbol) stack.pop());
                    }
                    else {
                        break;
                    }
                }
                stack.push(symbol);
            }
            else if(symbol == ParenthesisSymbol.LEFT) {
                stack.push(symbol);
            }
            else if(symbol == ParenthesisSymbol.RIGHT) {
                boolean foundLeftParen = false;
                while (!stack.isEmpty()) {
                    Symbol top = stack.peek();
                    if(top == ParenthesisSymbol.LEFT) {
                        foundLeftParen = true;
                        break;
                    }
                    else {
                        queue.add((PostfixNotationSymbol) stack.pop());
                    }
                }
                if(!foundLeftParen) {
                    throw new SyntaxException(MessageFormat.format(
                            "Syntax error in {0}: Mismatched parenthesis",
                            symbols));
                }
                else {
                    stack.pop();
                }
            }
            else {
                queue.add((PostfixNotationSymbol) symbol);
            }
        }
        while (!stack.isEmpty()) {
            Symbol top = stack.peek();
            if(top instanceof ParenthesisSymbol) {
                throw new SyntaxException(MessageFormat.format(
                        "Syntax error in {0}: Mismatched parenthesis", symbols));
            }
            else {
                queue.add((PostfixNotationSymbol) stack.pop());
            }
        }
        return queue;
    }

    /**
     * Convert a valid and well-formed CCL string into aQueue in postfix
     * notation.
     * <p>
     * NOTE: This method will group non-conjunctive symbols into
     * {@link Expression} objects.
     * </p>
     * 
     * @param ccl the string to parse into postfix notation
     * @return the queue in postfix notation
     */
    public static Queue<PostfixNotationSymbol> toPostfixNotation(String ccl) {
        return toPostfixNotation(ccl, null);
    }

    /**
     * Convert a valid and well-formed CCL string into a {@link Queue} in
     * postfix notation. This function will also resolve local references from
     * the CCL string into a {@link Multimap} passed in.
     * <p>
     * NOTE: This method will group non-conjunctive symbols into
     * {@link Expression} objects.
     * </p>
     *
     * @param ccl the CCL string to convert
     * @param data the data to use for local references
     * @return the queue in postfix notation
     */
    public static Queue<PostfixNotationSymbol> toPostfixNotation(String ccl,
            Multimap<String, Object> data) {
        // This method uses a value buffer to correct cases when a string value
        // is specified without quotes (because its a common mistake to make).
        // If an operator other than BETWEEN is specified, we use logic that
        // will buffer all the subsequent tokens until we reach a (parenthesis),
        // (conjunction) or (at) and assume that the tokens belong to the same
        // value.
        StringSplitter toks = new QuoteAwareStringSplitter(ccl, ' ',
                SplitOption.TOKENIZE_PARENTHESIS);
        List<Symbol> symbols = Lists.newArrayList();
        GuessState guess = GuessState.KEY;
        boolean shouldResolveVariableValue = false;
        KeySymbol resolvedVariableKey = null;
        OperatorSymbol resolvedVariableOperator = null;
        ConjunctionSymbol resolvedVariableConjunction = null;
        StringBuilder buffer = null;
        StringBuilder timeBuffer = null;
        while (toks.hasNext()) {
            String tok = toks.next();
            if(tok.equals("(") || tok.equals(")")) {
                addBufferedValue(buffer, symbols);
                addBufferedTime(timeBuffer, symbols);
                symbols.add(ParenthesisSymbol.parse(tok));
            }
            else if(tok.equalsIgnoreCase("&&") || tok.equalsIgnoreCase("&")
                    || tok.equalsIgnoreCase("and")) {
                addBufferedValue(buffer, symbols);
                addBufferedTime(timeBuffer, symbols);
                symbols.add(ConjunctionSymbol.AND);
                guess = GuessState.KEY;
            }
            else if(tok.equalsIgnoreCase("||") || tok.equalsIgnoreCase("or")) {
                addBufferedValue(buffer, symbols);
                addBufferedTime(timeBuffer, symbols);
                symbols.add(ConjunctionSymbol.OR);
                guess = GuessState.KEY;
            }
            else if(TIMESTAMP_PIVOT_TOKENS.contains(tok.toLowerCase())) {
                addBufferedValue(buffer, symbols);
                guess = GuessState.TIMESTAMP;
                timeBuffer = new StringBuilder();
            }
            else if(tok.equalsIgnoreCase("where")) {
                continue;
            }
            else if(StringUtils.isBlank(tok)) {
                continue;
            }
            else if(guess == GuessState.KEY) {
                if(tok.charAt(0) == '$') {
                    if(tok.length() > 2 && tok.charAt(1) == '$') {
                        tok = tok.substring(2);
                        resolvedVariableConjunction = ConjunctionSymbol.AND;
                    }
                    else {
                        tok = tok.substring(1);
                        resolvedVariableConjunction = ConjunctionSymbol.OR;
                    }
                    shouldResolveVariableValue = true;
                    symbols.add(ParenthesisSymbol.LEFT);
                    resolvedVariableKey = KeySymbol.parse(tok);
                    symbols.add(resolvedVariableKey);
                }
                else {
                    symbols.add(KeySymbol.parse(tok));
                }
                guess = GuessState.OPERATOR;
            }
            else if(guess == GuessState.OPERATOR) {
                OperatorSymbol symbol = OperatorSymbol.parse(tok);
                symbols.add(symbol);
                if(symbol.getOperator() != Operator.BETWEEN) {
                    buffer = new StringBuilder();
                }
                if(shouldResolveVariableValue) {
                    resolvedVariableOperator = symbol;
                }
                guess = GuessState.VALUE;
            }
            else if(guess == GuessState.VALUE) {
                if(shouldResolveVariableValue) {
                    if(resolvedVariableOperator.getOperator() == Operator.BETWEEN) {
                        if(symbols.get(symbols.size() - 1) instanceof ValueSymbol) {
                            String prevTok = symbols.get(symbols.size() - 1).toString();
                            String reference1 = parseReferenceToken(prevTok);
                            String reference2 = parseReferenceToken(tok);
                            Set<Object> values1 = (Set) data.get(reference1);
                            Set<Object> values2 = (Set) data.get(reference2);

                            if(reference1.equals(prevTok)
                                    && reference2.equals(tok)) {
                                throw new IllegalStateException(""
                                        + "Expected at least one variable, "
                                        + "but found " + prevTok + " and " + tok);
                            }

                            if((values1 == null || values1.isEmpty())
                                    && (values2 == null || values2.isEmpty())) {
                                throw new IllegalStateException(""
                                        + "Local references for " + reference1
                                        + " and " + reference2 + " not found");
                            }
                            else if(values1 == null || values1.isEmpty()) {
                                values1 = Sets.newHashSet((Object) reference1);
                            }
                            else if(values2 == null || values2.isEmpty()) {
                                values2 = Sets.newHashSet((Object) reference2);
                            }

                            // Clear previous symbols to start loop from beginning
                            symbols.remove(symbols.size() - 1);
                            symbols.remove(symbols.size() - 1);
                            symbols.remove(symbols.size() - 1);

                            for (Object value1 : values1) {
                                for (Object value2 : values2) {
                                    symbols.add(resolvedVariableKey);
                                    symbols.add(resolvedVariableOperator);
                                    symbols.add(ValueSymbol.parse(value1.toString()));
                                    symbols.add(ValueSymbol.parse(value2.toString()));
                                    symbols.add(resolvedVariableConjunction);
                                }
                            }

                            symbols.remove(symbols.size() - 1); // Remove extra conjunction
                            symbols.add(ParenthesisSymbol.RIGHT);
                            shouldResolveVariableValue = false;
                        }
                        else {
                            symbols.add(ValueSymbol.parse(tok));
                        }
                    }
                    else {
                        String reference = parseReferenceToken(tok);

                        if(reference.equals(tok)) {
                            throw new IllegalStateException(""
                                    + "Expected a $ or $$ before variable, "
                                    + "but got " + tok);
                        }

                        Set<Object> values = (Set) data.get(reference);

                        if(values == null || values.isEmpty()) {
                            throw new IllegalStateException("Local reference "
                                    + tok + " not found");
                        }
                        else {
                            // Clear previous symbols
                            symbols.remove(symbols.size() - 1);
                            symbols.remove(symbols.size() - 1);

                            for (Object value : values) {
                                symbols.add(resolvedVariableKey);
                                symbols.add(resolvedVariableOperator);
                                symbols.add(ValueSymbol.parse(value.toString()));
                                symbols.add(resolvedVariableConjunction);
                            }

                            symbols.remove(symbols.size() - 1); // Remove extra conjunction
                            symbols.add(ParenthesisSymbol.RIGHT);
                            shouldResolveVariableValue = false;
                        }
                    }
                }
                else {
                    if(buffer != null) {
                        buffer.append(tok).append(" ");
                    }
                    else {
                        symbols.add(ValueSymbol.parse(tok));
                    }
                }
            }
            else if(guess == GuessState.TIMESTAMP) {
                timeBuffer.append(tok).append(" ");
            }
            else {
                throw new IllegalStateException("Cannot properly parse " + tok);
            }
        }
        addBufferedValue(buffer, symbols);
        addBufferedTime(timeBuffer, symbols);
        return toPostfixNotation(symbols);
    }

    /**
     * Go through a list of symbols and group the expressions together in a
     * {@link Expression} object.
     * 
     * @param symbols
     * @return the expression
     */
    protected static List<Symbol> groupExpressions(List<Symbol> symbols) { // visible
                                                                           // for
                                                                           // testing
        try {
            List<Symbol> grouped = Lists.newArrayList();
            ListIterator<Symbol> it = symbols.listIterator();
            while (it.hasNext()) {
                Symbol symbol = it.next();
                if(symbol instanceof KeySymbol) {
                    // NOTE: We are assuming that the list of symbols is well
                    // formed, and, as such, the next elements will be an
                    // operator and one or more symbols. If this is not the
                    // case, this method will throw a ClassCastException
                    OperatorSymbol operator = (OperatorSymbol) it.next();
                    ValueSymbol value = (ValueSymbol) it.next();
                    Expression expression;
                    if(operator.getOperator() == Operator.BETWEEN) {
                        ValueSymbol value2 = (ValueSymbol) it.next();
                        expression = Expression.create((KeySymbol) symbol,
                                operator, value, value2);
                    }
                    else {
                        expression = Expression.create((KeySymbol) symbol,
                                operator, value);
                    }
                    grouped.add(expression);
                }
                else if(symbol instanceof TimestampSymbol) { // Add the
                                                             // timestamp to the
                                                             // previously
                                                             // generated
                                                             // Expression
                    ((Expression) Iterables.getLast(grouped))
                            .setTimestamp((TimestampSymbol) symbol);
                }
                else {
                    grouped.add(symbol);
                }
            }
            return grouped;
        }
        catch (ClassCastException e) {
            throw new SyntaxException(e.getMessage());
        }
    }

    /**
     * An the appropriate {@link AST} node to the {@code stack} based on
     * {@code operator}.
     * 
     * @param stack
     * @param operator
     */
    private static void addASTNode(Deque<AST> stack, Symbol operator) {
        AST right = stack.pop();
        AST left = stack.pop();
        if(operator == ConjunctionSymbol.AND) {
            stack.push(AndTree.create(left, right));
        }
        else {
            stack.push(OrTree.create(left, right));
        }
    }

    /**
     * This is a helper method for {@link #toPostfixNotation(String)} that
     * contains the logic to create a ValueSymbol from a buffered value.
     * 
     * @param buffer
     * @param symbols
     */
    private static void addBufferedValue(StringBuilder buffer,
            List<Symbol> symbols) {
        if(buffer != null && buffer.length() > 0) {
            buffer.delete(buffer.length() - 1, buffer.length());
            symbols.add(ValueSymbol.parse(buffer.toString()));
            buffer.delete(0, buffer.length());
        }
    }

    private static void addBufferedTime(StringBuilder buffer,
            List<Symbol> symbols) {
        if(buffer != null && buffer.length() > 0) {
            buffer.delete(buffer.length() - 1, buffer.length());
            long ts = NaturalLanguage.parseMicros(buffer.toString());
            symbols.add(TimestampSymbol.create(ts));
            buffer.delete(0, buffer.length());
        }
    }

    /**
     * A helper method for {@link #toPostfixNotation(String, Multimap)} to
     * parse tokens that are possible local references.
     *
     * @param tok the token to parse
     * @return the parsed and corrected to token
     */
    private static String parseReferenceToken(String tok) {
        if(tok.charAt(0) == '$') {
            if(tok.length() > 2 && tok.charAt(1) == '$') {
                return tok.substring(2);
            }
            else {
                return tok.substring(1);
            }
        }
        else if(tok.length() > 2 && tok.charAt(0) == '\\'
                && tok.charAt(1) == '$') {
            return tok.substring(1);
        }
        return tok;
    }

    /**
     * A collection of tokens that indicate the parser should pivot to expecting
     * a timestamp token.
     */
    private final static Set<String> TIMESTAMP_PIVOT_TOKENS = Sets.newHashSet(
            "at", "on", "during", "in");

    private Parser() {/* noop */}

    /**
     * An enum that tracks what the parser guesses the next token to be in the
     * {@link #toPostfixNotation(String)} method.
     * 
     * @author Jeff Nelson
     */
    private enum GuessState {
        KEY, OPERATOR, TIMESTAMP, VALUE
    }
}
