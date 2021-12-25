/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.atomgraph.core.riot.lang;

import static org.apache.jena.atlas.lib.Chars.*;
import static org.apache.jena.riot.system.RiotChars.*;

import java.util.NoSuchElementException;

import org.apache.jena.atlas.AtlasException;
import org.apache.jena.atlas.io.IO;
import org.apache.jena.atlas.io.PeekReader;
import org.apache.jena.atlas.lib.Chars;
import org.apache.jena.riot.RiotParseException;
import org.apache.jena.riot.system.ErrorHandler;
import org.apache.jena.riot.system.RiotChars;
import org.apache.jena.riot.tokens.StringType;
import org.apache.jena.riot.tokens.Token;
import org.apache.jena.riot.tokens.TokenChecker;
import org.apache.jena.riot.tokens.TokenType;
import org.apache.jena.riot.tokens.Tokenizer;
import org.apache.jena.sparql.ARQInternalErrorException;

/** Tokenizer for all sorts of things RDF-ish */

public class TokenizerText implements Tokenizer
{
    // Drop through to final general symbol/keyword reader, including <=, != 
    // Care with <=
    // Policy driven for CURIES?
    
    // Various allow/deny options (via checker?)
    
    // Space for CURIEs, stricter Turtle QNames, sane Turtle (i.e. leading digits in local part).
    public static final int CTRL_CHAR = CH_STAR;
    
    public static boolean Checking = false;

    private Token token = null; 
    private final StringBuilder stringBuilder = new StringBuilder(200);
    private final PeekReader reader;
    private final boolean lineMode;        // Whether whitespace includes or excludes NL (in its various forms).  
    private boolean finished = false;
    private TokenChecker checker = null;

    private static class ErrorHandlerTokenizer implements ErrorHandler {
        @Override public void warning(String message, long line, long col) {
            // Warning/continue.
            //ErrorHandlerFactory.errorHandlerStd.warning(message, line, col);
            throw new RiotParseException(message, line, col);
        }

        @Override public void error(String message, long line, long col) {
            throw new RiotParseException(message, line, col);
        }

        @Override public void fatal(String message, long line, long col) {
            throw new RiotParseException(message, line, col);
        }
    };
    // The code assumes that errors throw exception and so stop parsing.
    private static final ErrorHandler defaultErrorHandler = new ErrorHandlerTokenizer();
    private ErrorHandler errorHandler = defaultErrorHandler;

    TokenizerText(PeekReader reader) {
        this(reader, false);
    }
    
    TokenizerText(PeekReader reader, boolean lineMode) {
        this.reader = reader;
        this.lineMode = lineMode;
    }
    
    public boolean getFinished()
    {
        return finished;
    }
    
    public PeekReader getReader()
    {
        return reader;
    }
    
    @Override
    public final boolean hasNext() {
        if ( finished )
            return false;
        if ( token != null )
            return true;

        try {
            skip();
            if ( getReader().eof() ) {
                // close();
                finished = true;
                return false;
            }
            token = parseToken();
            if ( token == null ) {
                // close();
                finished = true;
                return false;
            }
            return true;
        } catch (AtlasException ex) {
            if ( ex.getCause() != null ) {
                if ( ex.getCause().getClass() == java.nio.charset.MalformedInputException.class )
                    throw new RiotParseException("Bad character encoding", getReader().getLineNum(), getReader().getColNum());
                throw new RiotParseException("Bad input stream [" + ex.getCause() + "]", getReader().getLineNum(),
                                             getReader().getColNum());
            }
            throw new RiotParseException("Bad input stream", getReader().getLineNum(), getReader().getColNum());
        }
    }
    
    
    @Override
    public final boolean eof() {
        return !hasNext();
    }

    @Override
    public final Token next() {
        if ( !hasNext() )
            throw new NoSuchElementException();
        Token t = token;
        token = null;
        return t;
    }

    @Override
    public final Token peek() {
        if ( !hasNext() )
            return null;
        return token;
    }
    
    @Override
    public void remove()                            
    { throw new UnsupportedOperationException(); }

    public TokenChecker getChecker() {
        return checker;
    }

    public void setChecker(TokenChecker checker) {
        this.checker = checker;
    }

    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }
    
    @Override
    public void close() {
        IO.close(reader);
    }

    // ---- Machinary
    
    private void skip() {
        int ch = EOF;
        for (;;) {
            if ( getReader().eof() )
                return;

            ch = getReader().peekChar();
            if ( ch == CH_HASH ) {
                getReader().readChar();
                // Comment. Skip to NL
                for (;;) {
                    ch = getReader().peekChar();
                    if ( ch == EOF || isNewlineChar(ch) )
                        break;
                    getReader().readChar();
                }
            }

            // Including excess newline chars from comment.
            if ( lineMode ) {
                if ( !isHorizontalWhitespace(ch) )
                    break;
            } else {
                if ( !isWhitespace(ch) )
                    break;
            }
            getReader().readChar();
        }
    }

    protected Token parseToken() {
        token = new Token(getLine(), getColumn());

        int ch = getReader().peekChar();

        // ---- IRI
        if ( ch == CH_LT ) {
            getReader().readChar();
            token.setImage(readIRI());
            token.setType(TokenType.IRI);
            if ( Checking )
                checkURI(token.getImage());
            return token;
        }

        // ---- Literal
        if ( ch == CH_QUOTE1 || ch == CH_QUOTE2 ) {
            // The token type is STRING.
            // We incorporate this into a token for LITERAL_LANG or LITERAL_DT.  
            token.setType(TokenType.STRING);
            
            getReader().readChar();
            int ch2 = getReader().peekChar();
            if ( ch2 == ch ) {
                getReader().readChar(); // Read potential second quote.
                int ch3 = getReader().peekChar();
                if ( ch3 == ch ) {
                    getReader().readChar();     // Read potential third quote.
                    token.setImage(readLongString(ch, false));
                    StringType st = (ch == CH_QUOTE1) ? StringType.LONG_STRING1 : StringType.LONG_STRING2;
                    token.setStringType(st);
                } else {
                    // Two quotes then a non-quote.
                    // Must be '' or ""
                    // No need to pushback characters as we know the lexical
                    // form is the empty string.
                    // if ( ch2 != EOF ) getReader().pushbackChar(ch2);
                    // if ( ch1 != EOF ) getReader().pushbackChar(ch1); // Must be
                    // '' or ""
                    token.setImage("");
                    StringType st = (ch == CH_QUOTE1) ? StringType.STRING1 : StringType.STRING2;
                    token.setStringType(st);
                }
            } else {
                // One quote character.
                token.setImage(readString(ch, ch));
                // Record exactly what form of STRING was seen.
                StringType st = (ch == CH_QUOTE1) ? StringType.STRING1 : StringType.STRING2;
                token.setStringType(st);
            }

            // White space after lexical part of a literal.
            skip();
            
            // Literal. Is it @ or ^^
            if ( getReader().peekChar() == CH_AT ) {
                getReader().readChar();
                // White space is not legal here.
                // The Turtle spec terminal is "LANGTAG" which includes the '@'.
                Token mainToken = new Token(token);
                mainToken.setType(TokenType.LITERAL_LANG);
                mainToken.setSubToken1(token);
                mainToken.setImage2(langTag());
                token = mainToken;
                if ( Checking )
                    checkLiteralLang(token.getImage(), token.getImage2());
            } else if ( getReader().peekChar() == '^' ) {
                expect("^^");
                // White space is legal after a ^^. 
                // It's not a good idea, but it is legal.
//                // Check no whitespace.
//                int nextCh = getReader().peekChar();
//                if ( isWhitespace(nextCh) )
//                    exception("No whitespace after ^^ in literal with datatype");
                skip();
                
                // Stash current token.
                Token mainToken = new Token(token);
                mainToken.setSubToken1(token);
                mainToken.setImage(token.getImage());

                Token subToken = parseToken();
                if ( !subToken.isIRI() )
                    error("Datatype URI required after ^^ - URI or prefixed name expected");

                mainToken.setSubToken2(subToken);
                mainToken.setType(TokenType.LITERAL_DT);

                token = mainToken;
                if ( Checking )
                    checkLiteralDT(token.getImage(), subToken);
            } else {
                // Was a simple string.
                if ( Checking )
                    checkString(token.getImage());
            }
            return token;
        }

        if ( ch == CH_UNDERSCORE ) {
            getReader().readChar();
            int ch2 = getReader().peekChar();
            if ( ch2 == CH_COLON ) {
                getReader().readChar();
                // Blank node :label must be at least one char
                token.setImage(readBlankNodeLabel());
                token.setType(TokenType.BNODE);
                if ( Checking ) checkBlankNode(token.getImage());
                return token;
            }
            token.setType(TokenType.UNDERSCORE);
            /*token.setImage(CH_UNDERSCORE);*/
            return token;
        }

        // A directive (not part of a literal as lang tag)
        if ( ch == CH_AT ) {
            getReader().readChar();
            token.setType(TokenType.DIRECTIVE);
            token.setImage(readWord(false));
            if ( Checking )
                checkDirective(token.cntrlCode);
            return token;
        }

        // Variable
        if ( ch == CH_QMARK ) {
            getReader().readChar();
            token.setType(TokenType.VAR);
            // Character set?
            token.setImage(readVarName());
            if ( Checking )
                checkVariable(token.getImage());
            return token;
        }
        
        switch(ch)
        { 
            // DOT can start a decimal.  Check for digit.
            case CH_DOT:
                getReader().readChar();
                ch = getReader().peekChar();
                if ( range(ch, '0', '9') ) {
                    // Not a DOT after all.
                    getReader().pushbackChar(CH_DOT);
                    readNumber();
                    if ( Checking )
                        checkNumber(token.getImage(), token.getImage2());
                    return token;
                }
                token.setType(TokenType.DOT);
                return token;
            
            case CH_SEMICOLON:  getReader().readChar(); token.setType(TokenType.SEMICOLON); /*token.setImage(CH_SEMICOLON);*/ return token;
            case CH_COMMA:      getReader().readChar(); token.setType(TokenType.COMMA);     /*token.setImage(CH_COMMA);*/ return token;
            case CH_LBRACE:     getReader().readChar(); token.setType(TokenType.LBRACE);    /*token.setImage(CH_LBRACE);*/ return token;
            case CH_RBRACE:     getReader().readChar(); token.setType(TokenType.RBRACE);    /*token.setImage(CH_RBRACE);*/ return token;
            case CH_LPAREN:     getReader().readChar(); token.setType(TokenType.LPAREN);    /*token.setImage(CH_LPAREN);*/ return token;
            case CH_RPAREN:     getReader().readChar(); token.setType(TokenType.RPAREN);    /*token.setImage(CH_RPAREN);*/ return token;
            case CH_LBRACKET:   getReader().readChar(); token.setType(TokenType.LBRACKET);  /*token.setImage(CH_LBRACKET);*/ return token;
            case CH_RBRACKET:   getReader().readChar(); token.setType(TokenType.RBRACKET);  /*token.setImage(CH_RBRACKET);*/ return token;
            case CH_EQUALS:     getReader().readChar(); token.setType(TokenType.EQUALS);    /*token.setImage(CH_EQUALS);*/ return token;
            case CH_SLASH:      getReader().readChar(); token.setType(TokenType.SLASH);     /*token.setImage(CH_SLASH);*/ return token;
            case CH_RSLASH:     getReader().readChar(); token.setType(TokenType.RSLASH);    /*token.setImage(CH_RSLASH);*/ return token;
            case CH_VBAR:       getReader().readChar(); token.setType(TokenType.VBAR);      /*token.setImage(CH_VBAR);*/ return token;
            case CH_AMPHERSAND: getReader().readChar(); token.setType(TokenType.AMPERSAND);/*token.setImage(CH_AMPHERSAND);*/ return token;
            // Specials (if blank node processing off)
            //case CH_COLON:      getReader().readChar(); token.setType(TokenType.COLON); return token;

            // Done above with blank nodes.
            //case CH_UNDERSCORE: getReader().readChar(); token.setType(TokenType.UNDERSCORE); /*token.setImage(CH_UNDERSCORE);*/ return token;
            case CH_LT:         getReader().readChar(); token.setType(TokenType.LT); /*token.setImage(CH_LT);*/ return token;
            case CH_GT:         getReader().readChar(); token.setType(TokenType.GT); /*token.setImage(CH_GT);*/ return token;
            case CH_STAR:       getReader().readChar(); token.setType(TokenType.STAR); /*token.setImage(CH_STAR);*/ return token;

            // XXX Multi character symbols
            // Two character tokens && || GE >= , LE <=
            //TokenType.LE
            //TokenType.GE
            //TokenType.LOGICAL_AND
            //TokenType.LOGICAL_OR
        }
        
        // ---- Numbers.
        // A plain "+" and "-", not followed by a digit, are symbols.
        
        /*
        [16]    integer         ::=     ('-' | '+') ? [0-9]+
        [17]    double          ::=     ('-' | '+') ? ( [0-9]+ '.' [0-9]* exponent | '.' ([0-9])+ exponent | ([0-9])+ exponent )
                                        0.e0, .0e0, 0e0
        [18]    decimal         ::=     ('-' | '+')? ( [0-9]+ '.' [0-9]* | '.' ([0-9])+ | ([0-9])+ )
                                        0.0 .0 0.
        [19]    exponent        ::=     [eE] ('-' | '+')? [0-9]+
        []      hex             ::=     0x0123456789ABCDEFG
        
        */
        
        // TODO readNumberNoSign
        
        int signCh = 0;

        if ( ch == CH_PLUS || ch == CH_MINUS ) {
            getReader().readChar();
            int ch2 = getReader().peekChar();

            if ( !range(ch2, '0', '9') ) {
                // ch was end of symbol.
                // getReader().readChar();
                if ( ch == CH_PLUS )
                    token.setType(TokenType.PLUS);
                else
                    token.setType(TokenType.MINUS);
                return token;
            }

            // Already got a + or - ...
            // readNumberNoSign
            // Because next, old code proceses signs.
            getReader().pushbackChar(ch);
            signCh = ch;
            // Drop to next "if"
        }

        if ( ch == CH_PLUS || ch == CH_MINUS || range(ch, '0', '9') ) {
            // readNumberNoSign
            readNumber();
            if ( Checking )
                checkNumber(token.getImage(), token.getImage2());
            return token;
        }

        if ( isNewlineChar(ch) ) {
            //** - If collecting token image.
            //** stringBuilder.setLength(0);
            // Any number of NL and CR become one "NL" token.
            do {
                int ch2 = getReader().readChar();
                //** stringBuilder.append((char)ch2);
            } while (isNewlineChar(getReader().peekChar()));
            token.setType(TokenType.NL);
            //** token.setImage(stringBuilder.toString());
            return token;
        }
        
        // Plain words and prefixes.
        //   Can't start with a number due to numeric test above.
        //   Can't start with a '_' due to blank node test above.
        // If we see a :, the first time it means a prefixed name else it's a token break.
        
        readPrefixedNameOrKeyword(token);
        
        if ( Checking ) checkKeyword(token.getImage());
        return token;
    }

    
    private static final boolean VeryVeryLaxIRI = false;
    // Spaces in IRI are illegal.
    private static final boolean AllowSpacesInIRI = false;
    
    // [8]  IRIREF  ::= '<' ([^#x00-#x20<>"{}|^`\] | UCHAR)* '>'
    private String readIRI() {
        stringBuilder.setLength(0);
        for (;;) {
            int ch = getReader().readChar();
            switch(ch) {
                case EOF:
                    error("Broken IRI (End of file)"); return null;
                case NL:
                    error("Broken IRI (newline): %s", stringBuilder.toString()); return null;
                case CR:
                    error("Broken IRI (CR): %s", stringBuilder.toString()); return null;
                case CH_GT:
                    // Done!
                    return stringBuilder.toString();
                case CH_RSLASH:
                    if ( VeryVeryLaxIRI )
                        // Includes unicode escapes and also \n etc 
                        ch = readLiteralEscape();
                    else
                        // NORMAL
                        ch = readUnicodeEscape();
                    // Don't check legality of ch (strict syntax at this point).
                    // That does not mean it is a good idea to bypass checking.
                    // Bad characters will lead to trouble elsewhere.
                    break;
                case CH_LT:
                    // Probably a corrupt file so not a warning.
                    error("Bad character in IRI (bad character: '<'): <%s[<]...>", stringBuilder.toString()); return null;
                case TAB:
                    error("Bad character in IRI (Tab character): <%s[tab]...>", stringBuilder.toString()); return null;
                case '{': case '}': case '"': case '|': case '^': case '`' :
                    if ( ! VeryVeryLaxIRI )
                        warning("Illegal character in IRI (codepoint 0x%02X, '%c'): <%s[%c]...>", ch, (char)ch, stringBuilder.toString(), (char)ch);
                    break;
                case SPC:
                    if ( ! AllowSpacesInIRI )
                        warning("Bad character in IRI (space): <%s[space]...>", stringBuilder.toString());
                    break;
                default:
                    if ( ch <= 0x19 )
                        warning("Illegal character in IRI (control char 0x%02X): <%s[0x%02X]...>", ch, stringBuilder.toString(), ch);
            }
            insertCodepoint(stringBuilder, ch);
        }
    }
    
    // Read a unicode escape : does not allow \\ bypass
    private int readUnicodeEscape() {
        int ch = getReader().readChar();
        if ( ch == EOF )
            error("Broken escape sequence");
    
        switch (ch) {
            case 'u': return readUnicode4Escape(); 
            case 'U': return readUnicode8Escape(); 
            default:
                error("Illegal unicode escape sequence value: \\%c (0x%02X)", ch, ch);
        }
        return 0;
    }

    private void readPrefixedNameOrKeyword(Token token) {
        long posn = getReader().getPosition();
        String prefixPart = readPrefixPart(); // Prefix part or keyword
        token.setImage(prefixPart);
        token.setType(TokenType.KEYWORD);
        int ch = getReader().peekChar();
        if ( ch == CH_COLON ) {
            getReader().readChar();
            token.setType(TokenType.PREFIXED_NAME);
            String ln = readLocalPart(); // Local part
            token.setImage2(ln);
            if ( Checking )
                checkPrefixedName(token.getImage(), token.getImage2());
        }

        // If we made no progress, nothing found, not even a keyword -- it's an
        // error.
        if ( posn == getReader().getPosition() )
            error("Failed to find a prefix name or keyword: %c(%d;0x%04X)", ch, ch, ch);

        if ( Checking )
            checkKeyword(token.getImage());

    }
    
    /*
    The token rules from SPARQL and Turtle.
    PNAME_NS       ::=  PN_PREFIX? ':'
    PNAME_LN       ::=  PNAME_NS PN_LOCAL
    
    PN_CHARS_BASE  ::=  [A-Z] | [a-z] | [#x00C0-#x00D6] | [#x00D8-#x00F6] | [#x00F8-#x02FF] | [#x0370-#x037D] | [#x037F-#x1FFF] | [#x200C-#x200D] | [#x2070-#x218F] | [#x2C00-#x2FEF] | [#x3001-#xD7FF] | [#xF900-#xFDCF] | [#xFDF0-#xFFFD] | [#x10000-#xEFFFF]
    PN_CHARS_U  ::=  PN_CHARS_BASE | '_'
    PN_CHARS  ::=  PN_CHARS_U | '-' | [0-9] | #x00B7 | [#x0300-#x036F] | [#x203F-#x2040]
    
    PN_PREFIX  ::=  PN_CHARS_BASE ((PN_CHARS|'.')* PN_CHARS)?
    PN_LOCAL  ::=  (PN_CHARS_U | ':' | [0-9] | PLX ) ((PN_CHARS | '.' | ':' | PLX)* (PN_CHARS | ':' | PLX) )?
    PLX  ::=  PERCENT | PN_LOCAL_ESC
    PERCENT  ::=  '%' HEX HEX
    HEX  ::=  [0-9] | [A-F] | [a-f]
    PN_LOCAL_ESC  ::=  '\' ( '_' | '~' | '.' | '-' | '!' | '$' | '&' | "'" | '(' | ')' | '*' | '+' | ',' | ';' | '=' | '/' | '?' | '#' | '@' | '%' )
    */
    
    private String readPrefixPart()
    { return readSegment(false); }
    
    private String readLocalPart()
    { return readSegment(true); }

    private String readSegment(boolean isLocalPart) { 
        // Prefix: PN_CHARS_BASE                       ((PN_CHARS|'.')* PN_CHARS)?
        // Local: ( PN_CHARS_U | ':' | [0-9] | PLX )   ((PN_CHARS | '.' | ':' | PLX)* (PN_CHARS | ':' | PLX) )?

        // RiotChars has isPNChars_U_N for   ( PN_CHARS_U | [0-9] )
        stringBuilder.setLength(0);

        // -- Test first character
        int ch = getReader().peekChar();
        if ( ch == EOF )
            return "";
        if ( isLocalPart ) {
            if ( ch == CH_COLON ) {
                getReader().readChar();
                stringBuilder.append((char)ch);
            }

            // processPLX
            else if ( ch == CH_PERCENT || ch == CH_RSLASH ) {
                getReader().readChar();
                processPLX(ch);
            } else if ( RiotChars.isPNChars_U_N(ch) ) {
                stringBuilder.append((char)ch);
                getReader().readChar();
            } else
                return "";
        } else {
            if ( !RiotChars.isPNCharsBase(ch) )
                return "";
            stringBuilder.append((char)ch);
            getReader().readChar();
        }
        // Done first character
        int chDot = 0;

        for (;;) {
            ch = getReader().peekChar();
            boolean valid = false;

            if ( isLocalPart && (ch == CH_PERCENT || ch == CH_RSLASH) ) {
                getReader().readChar();
                if ( chDot != 0 )
                    stringBuilder.append((char)chDot);
                processPLX(ch);
                chDot = 0;
                continue;
            }

            // Single valid characters
            if ( isLocalPart && ch == CH_COLON )
                valid = true;
            else if ( isPNChars(ch) )
                valid = true;
            else if ( ch == CH_DOT )
                valid = true;
            else
                valid = false;

            if ( !valid )
                break; // Exit loop

            // Valid character.
            getReader().readChar();
            // Was there also a DOT previous loop?
            if ( chDot != 0 ) {
                stringBuilder.append((char)chDot);
                chDot = 0;
            }

            if ( ch != CH_DOT )
                stringBuilder.append((char)ch);
            else
                // DOT - delay until next loop.
                chDot = ch;
        }

        // On exit, chDot may hold a character.

        if ( chDot == CH_DOT )
            // Unread it.
            getReader().pushbackChar(chDot);
        return stringBuilder.toString();
    }

    // Process PLX (percent or character escape for a prefixed name)
    private void processPLX(int ch)
    {
        if ( ch == CH_PERCENT )
        {
            stringBuilder.append((char)ch);

            ch = getReader().peekChar();
            if ( ! isHexChar(ch) )
                error("Not a hex character: '%c'",ch);
            stringBuilder.append((char)ch);
            getReader().readChar();

            ch = getReader().peekChar();
            if ( ! isHexChar(ch) )
                error("Not a hex character: '%c'",ch);
            stringBuilder.append((char)ch);
            getReader().readChar();
        }
        else if ( ch == CH_RSLASH )
        {
            ch = readCharEscape();
            stringBuilder.append((char)ch);
        }
        else
            throw new ARQInternalErrorException("Not a '\\' or a '%' character");
    }
    
    // Get characters between two markers.
    // strEscapes may be processed
    private String readString(int startCh, int endCh) {
        long y = getLine();
        long x = getColumn();
        stringBuilder.setLength(0);
        // Assumes first delimiter char read already.
        // Reads terminating delimiter 

        for (;;) {
            int ch = getReader().readChar();
            if ( ch == EOF ) {
                // if ( endNL ) return stringBuilder.toString();
                error("Broken token: " + stringBuilder.toString(), y, x);
            }

            if ( ch == NL )
                error("Broken token (newline): " + stringBuilder.toString(), y, x);

            if ( ch == endCh ) {
                return stringBuilder.toString();
            }

            if ( ch == CH_RSLASH )
                ch = readLiteralEscape();
            insertCodepoint(stringBuilder, ch);
        }
    }

    private String readLongString(int quoteChar, boolean endNL) {
        stringBuilder.setLength(0);
        for (;;) {
            int ch = getReader().readChar();
            if ( ch == EOF ) {
                if ( endNL )
                    return stringBuilder.toString();
                error("Broken long string");
            }

            if ( ch == quoteChar ) {
                if ( threeQuotes(quoteChar) )
                    return stringBuilder.toString();
            }

            if ( ch == CH_RSLASH )
                ch = readLiteralEscape();
            insertCodepoint(stringBuilder, ch);
        }
    }

    private String readWord(boolean leadingDigitAllowed)
    { return readWordSub(leadingDigitAllowed, false); }

    // A 'word' is used in several places:
    //   keyword
    //   prefix part of prefix name 
    //   local part of prefix name (allows digits)
    
    static private char[] extraCharsWord = new char[] {'_', '.' , '-'};
    
    private String readWordSub(boolean leadingDigitAllowed, boolean leadingSignAllowed) {
        return readCharsAnd(leadingDigitAllowed, leadingSignAllowed, extraCharsWord, false);
    }

    // This array adds the other characters that can occurs in an internal variable name.
    // Variables can be created with SPARQL-illegal syntax to ensure they do not clash with
    // variables in the query from the application.
    // See ARQConstants.
    //   allocVarAnonMarker, allocVarMarker, globalVar, allocVarBNodeToVar, allocVarScopeHiding
    // but this set is wider and matches anywhere in the name after the first '?'. 
    static private char[] extraCharsVar = new char[]{'_', '.', '-', '?', '@', '+', '/', '~'};

    private String readVarName() {
        return readCharsAnd(true, true, extraCharsVar, true);
    }
    
    // See also readBlankNodeLabel
    
    private String readCharsAnd(boolean leadingDigitAllowed, boolean leadingSignAllowed, char[] extraChars, boolean allowFinalDot) {
        stringBuilder.setLength(0);
        int idx = 0;
        if ( !leadingDigitAllowed ) {
            int ch = getReader().peekChar();
            if ( Character.isDigit(ch) )
                return "";
        }

        // Used for local part of prefix names =>
        if ( !leadingSignAllowed ) {
            int ch = getReader().peekChar();
            if ( ch == '-' || ch == '+' )
                return "";
        }

        for (;; idx++) {
            int ch = getReader().peekChar();

            if ( isAlphaNumeric(ch) || Chars.charInArray(ch, extraChars) ) {
                getReader().readChar();
                stringBuilder.append((char)ch);
                continue;
            } else
                // Inappropriate character.
                break;

        }

        if ( !allowFinalDot ) {
            // BAD : assumes pushbackChar is infinite.
            // Check is ends in "."
            while (idx > 0 && stringBuilder.charAt(idx - 1) == CH_DOT) {
                // Push back the dot.
                getReader().pushbackChar(CH_DOT);
                stringBuilder.setLength(idx - 1);
                idx--;
            }
        }
        return stringBuilder.toString();
    }

    // BLANK_NODE_LABEL    ::=     '_:' (PN_CHARS_U | [0-9]) ((PN_CHARS | '.')* PN_CHARS)?

    private String readBlankNodeLabel() {
        stringBuilder.setLength(0);
        // First character.
        {
            int ch = getReader().peekChar();
            if ( ch == EOF )
                error("Blank node label missing (EOF found)");
            if ( isWhitespace(ch) )
                error("Blank node label missing");
            // if ( ! isAlpha(ch) && ch != '_' )
            // Not strict

            if ( !RiotChars.isPNChars_U_N(ch) )
                error("Blank node label does not start with alphabetic or _ :" + (char)ch);
            getReader().readChar();
            stringBuilder.append((char)ch);
        }

        // Remainder. DOT can't be last so do a delay on that.

        int chDot = 0;

        for (;;) {
            int ch = getReader().peekChar();
            if ( ch == EOF )
                break;

            // DOT magic.
            if ( !(RiotChars.isPNChars(ch) || ch == CH_DOT) )
                break;
            getReader().readChar();

            if ( chDot != 0 ) {
                stringBuilder.append((char)chDot);
                chDot = 0;
            }

            if ( ch != CH_DOT )
                stringBuilder.append((char)ch);
            else
                // DOT - delay until next loop.
                chDot = ch;
        }

        if ( chDot == CH_DOT )
            // Unread it.
            getReader().pushbackChar(chDot);

        // if ( ! seen )
        // exception("Blank node label missing");
        return stringBuilder.toString();
    }

    /*
     * [146]  INTEGER  ::=  [0-9]+
     * [147]  DECIMAL  ::=  [0-9]* '.' [0-9]+
     * [148]  DOUBLE  ::=  [0-9]+ '.' [0-9]* EXPONENT | '.' ([0-9])+ EXPONENT | ([0-9])+ EXPONENT
     * []     hex             ::=     0x0123456789ABCDEFG
     */
    private void readNumber() {
        // One entry, definitely a number.
        // Beware of '.' as a (non) decimal.
        /*
        maybeSign()
        digits()
        if dot ==> decimal, digits
        if e   ==> double, maybeSign, digits
        else
            check not "." for decimal.
        */
        boolean isDouble = false;
        boolean isDecimal = false;
        stringBuilder.setLength(0);
        
        /*
        readPossibleSign(stringBuilder);
        readDigits may be hex
        readDot
        readDigits
        readExponent.
        */
        
        int x = 0; // Digits before a dot.
        int ch = getReader().peekChar();
        if ( ch == '0' ) {
            x++;
            getReader().readChar();
            stringBuilder.append((char)ch);
            ch = getReader().peekChar();
            if ( ch == 'x' || ch == 'X' ) {
                getReader().readChar();
                stringBuilder.append((char)ch);
                readHex(reader, stringBuilder);
                token.setImage(stringBuilder.toString());
                token.setType(TokenType.HEX);
                return;
            }
        } else if ( ch == '-' || ch == '+' ) {
            readPossibleSign(stringBuilder);
        }

        x += readDigits(stringBuilder);
//        if ( x == 0 ) {}
        ch = getReader().peekChar();
        if ( ch == CH_DOT ) {
            getReader().readChar();
            stringBuilder.append(CH_DOT);
            isDecimal = true; // Includes things that will be doubles.
            readDigits(stringBuilder);
        }

        if ( x == 0 && !isDecimal )
            // Possible a tokenizer error - should not have entered readNumber
            // in the first place.
            error("Unrecognized as number");

        if ( exponent(stringBuilder) ) {
            isDouble = true;
            isDecimal = false;
        }

        // Final part - "decimal" 123. is an integer 123 and a DOT.
        if ( isDecimal ) {
            int len = stringBuilder.length();
            if ( stringBuilder.charAt(len - 1) == CH_DOT ) {
                stringBuilder.setLength(len - 1);
                getReader().pushbackChar(CH_DOT);
                isDecimal = false;
            }
        }

        token.setImage(stringBuilder.toString());
        if ( isDouble )
            token.setType(TokenType.DOUBLE);
        else if ( isDecimal )
            token.setType(TokenType.DECIMAL);
        else
            token.setType(TokenType.INTEGER);
    }

    private void readHex(PeekReader reader, StringBuilder sb) {
        // Just after the 0x, which are in sb
        int x = 0;
        for (;;) {
            int ch = getReader().peekChar();

            if ( !isHexChar(ch) )
                break;
            getReader().readChar();
            sb.append((char)ch);
            x++;
        }
        if ( x == 0 )
            error("No hex characters after " + sb.toString());
    }

    private int readDigits(StringBuilder buffer) {
        int count = 0;
        for (;;) {
            int ch = getReader().peekChar();
            if ( !range(ch, '0', '9') )
                break;
            getReader().readChar();
            buffer.append((char)ch);
            count++;
        }
        return count;
    }

    private void readPossibleSign(StringBuilder sb) {
        int ch = getReader().peekChar();
        if ( ch == '-' || ch == '+' ) {
            getReader().readChar();
            sb.append((char)ch);
        }
    }

    // Assume have read the first quote char.
    // On return:
    //   If false, have moved over no more characters (due to pushbacks) 
    //   If true, at end of 3 quotes
    private boolean threeQuotes(int ch) {
        // getReader().readChar(); // Read first quote.
        int ch2 = getReader().peekChar();
        if ( ch2 != ch ) {
            // getReader().pushbackChar(ch2);
            return false;
        }

        getReader().readChar(); // Read second quote.
        int ch3 = getReader().peekChar();
        if ( ch3 != ch ) {
            // getReader().pushbackChar(ch3);
            getReader().pushbackChar(ch2);
            return false;
        }

        // Three quotes.
        getReader().readChar(); // Read third quote.
        return true;
    }

    private boolean exponent(StringBuilder sb) {
        int ch = getReader().peekChar();
        if ( ch != 'e' && ch != 'E' )
            return false;
        getReader().readChar();
        sb.append((char)ch);
        readPossibleSign(sb);
        int x = readDigits(sb);
        if ( x == 0 )
            error("Malformed double: " + sb);
        return true;
    }

    private String langTag() {
        stringBuilder.setLength(0);
        a2z(stringBuilder);
        if ( stringBuilder.length() == 0 )
            error("Bad language tag");
        for (;;) {
            int ch = getReader().peekChar();
            if ( ch == '-' ) {
                getReader().readChar();
                stringBuilder.append('-');
                int x = stringBuilder.length();
                a2zN(stringBuilder);
                if ( stringBuilder.length() == x )
                    error("Bad language tag");
            } else
                break;
        }
        return stringBuilder.toString().intern();
    }
    
    // ASCII-only e.g. in lang tags.
    private void a2z(StringBuilder sb2) {
        for (;;) {
            int ch = getReader().peekChar();
            if ( isA2Z(ch) ) {
                getReader().readChar();
                stringBuilder.append((char)ch);
            } else
                return;
        }
    }

    private void a2zN(StringBuilder sb2) {
        for (;;) {
            int ch = getReader().peekChar();
            if ( isA2ZN(ch) ) {
                getReader().readChar();
                stringBuilder.append((char)ch);
            } else
                return;
        }
    }

    private void insertCodepoint(StringBuilder buffer, int ch) {
        if ( Character.charCount(ch) == 1 )
            buffer.append((char)ch);
        else {
            // Convert to UTF-16. Note that the rest of any system this is used
            // in must also respect codepoints and surrogate pairs.
            if ( !Character.isDefined(ch) && !Character.isSupplementaryCodePoint(ch) )
                error("Illegal codepoint: 0x%04X", ch);
            char[] chars = Character.toChars(ch);
            buffer.append(chars);
        }
    }

    @Override
    public long getColumn() {
        return getReader().getColNum();
    }

    @Override
    public long getLine() {
        return getReader().getLineNum();
    }

    // ---- Routines to check tokens

    protected void checkBlankNode(String blankNodeLabel) {
        if ( checker != null )
            checker.checkBlankNode(blankNodeLabel);
    }

    protected void checkLiteralLang(String lexicalForm, String langTag) {
        if ( checker != null )
            checker.checkLiteralLang(lexicalForm, langTag);
    }

    protected void checkLiteralDT(String lexicalForm, Token datatype) {
        if ( checker != null )
            checker.checkLiteralDT(lexicalForm, datatype);
    }

    protected void checkString(String string) {
        if ( checker != null )
            checker.checkString(string);
    }

    protected void checkURI(String uriStr) {
        if ( checker != null )
            checker.checkURI(uriStr);
    }

    protected void checkNumber(String image, String datatype) {
        if ( checker != null )
            checker.checkNumber(image, datatype);
    }

    protected void checkVariable(String tokenImage) {
        if ( checker != null )
            checker.checkVariable(tokenImage);
    }

    protected void checkDirective(int cntrlCode) {
        if ( checker != null )
            checker.checkDirective(cntrlCode);
    }

    protected void checkKeyword(String tokenImage) {
        if ( checker != null )
            checker.checkKeyword(tokenImage);
    }

    protected void checkPrefixedName(String tokenImage, String tokenImage2) {
        if ( checker != null )
            checker.checkPrefixedName(tokenImage, tokenImage2);
    }

    protected void checkControl(int code) {
        if ( checker != null )
            checker.checkControl(code);
    }

    // ---- Escape sequences

    private int readLiteralEscape() {
        int c = getReader().readChar();
        if ( c == EOF )
            error("Escape sequence not completed");

        switch (c) {
            case 'n':   return NL; 
            case 'r':   return CR;
            case 't':   return '\t';
            case 'f':   return '\f';
            case 'b':   return BSPACE;
            case '"':   return '"';
            case '\'':  return '\'';
            case '\\':  return '\\';
            case 'u':   return readUnicode4Escape();
            case 'U':   return readUnicode8Escape();
            default:
                error("Illegal escape sequence value: %c (0x%02X)", c, c);
                return 0;
        }
    }

    private int readCharEscape() {
        // PN_LOCAL_ESC ::= '\' ( '_' | '~' | '.' | '-' | '!' | '$' | '&' | "'"
        // | '(' | ')' | '*' | '+' | ',' | ';' | '=' | '/' | '?' | '#' | '@' |
        // '%' )

        int c = getReader().readChar();
        if ( c == EOF )
            error("Escape sequence not completed");

        switch (c) {
            case '_': case '~': case '.':  case '-':  case '!':  case '$':  case '&': 
            case '\'':  
            case '(':  case ')':  case '*':  case '+':  case ',':  case ';': 
            case '=':  case '/':  case '?':  case '#':  case '@':  case '%':
                return c;
            default:
                error("illegal character escape value: \\%c", c);
                return 0;
        }
    }
    
    private int readUnicode4Escape() { return readHexSequence(4); }
    
    private int readUnicode8Escape() {
        int ch8 = readHexSequence(8);
        if ( ch8 > Character.MAX_CODE_POINT )
            error("Illegal code point in \\U sequence value: 0x%08X", ch8);
        return ch8;
    }

    private int readHexSequence(int N) {
        int x = 0;
        for (int i = 0; i < N; i++) {
            int d = readHexChar();
            if ( d < 0 )
                return -1;
            x = (x << 4) + d;
        }
        return x;
    }

    private int readHexChar() {
        int ch = getReader().readChar();
        if ( ch == EOF )
            error("Not a hexadecimal character (end of file)");

        int x = valHexChar(ch);
        if ( x != -1 )
            return x;
        error("Not a hexadecimal character: " + (char)ch);
        return -1;
    }

    private boolean expect(String str) {
        for (int i = 0; i < str.length(); i++) {
            char want = str.charAt(i);
            if ( getReader().eof() ) {
                error("End of input during expected string: " + str);
                return false;
            }
            int inChar = getReader().peekChar();
            if ( inChar != want ) {
                error("expected \"" + str + "\"");
                return false;
            }
            getReader().readChar();
        }
        return true;
    }

    private void warning(String message, Object... args) {
        String msg = String.format(message, args);
        errorHandler.warning(msg, getReader().getLineNum(), getReader().getColNum());
        //exception(message, args); 
    }
    
    private void error(String message, Object... args) {
        String msg = String.format(message, args);
        long line = getReader().getLineNum();
        long col = getReader().getColNum();
        errorHandler.error(msg, line, col);
        // We require that errors cause the tokenizer to stop so in case the
        // provided error handler does not, we throw an exception.
        throw new RiotParseException(message, line, col);
    }
}