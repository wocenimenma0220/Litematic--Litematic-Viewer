package io.github.litematicrender.serve;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.io.*;

/** Dependency-free application exporter: typed OOXML values and UTF-8 BOM CSV. */
final class MaterialExporter {
    @SuppressWarnings("unchecked")
    static void write(Path file,Map<String,Object> report,String scope,String source)throws IOException {
        if(!Set.of("combined","building","contents").contains(scope))throw new IllegalArgumentException("未知材料导出范围");
        String extension=file.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!extension.endsWith(".xlsx")&&!extension.endsWith(".csv"))throw new IllegalArgumentException("材料表仅支持 XLSX 和 CSV");
        var building=(List<Map<String,Object>>)report.get("building");var contents=(List<Map<String,Object>>)report.get("contents");
        var rows=new ArrayList<List<Object>>();
        if(scope.equals("combined"))rows.add(List.of("建材","数量","需盒","","容器内容物","数量","需盒"));
        else rows.add(List.of(scope.equals("building")?"建材":"容器内容物","数量","需盒"));
        int size=scope.equals("building")?building.size():scope.equals("contents")?contents.size():Math.max(building.size(),contents.size());
        for(int i=0;i<size;i++){
            var row=new ArrayList<Object>();
            if(!scope.equals("contents"))append(row,building,i);
            if(scope.equals("combined"))row.add("");
            if(!scope.equals("building"))append(row,contents,i);rows.add(row);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());Path tmp=Files.createTempFile(file.toAbsolutePath().getParent(),".materials-",".tmp");
        try {
            if(extension.endsWith(".csv"))csv(tmp,rows);else {var notes=new ArrayList<>((List<String>)report.get("warnings"));if(Boolean.TRUE.equals(report.get("waterAsIce")))notes.add("建材中的水桶按 1 桶水 = 1 块普通冰换算；容器内容物保持保存的物品。");xlsx(tmp,rows,source,notes);}
            Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp);}
    }
    private static void append(List<Object> row,List<Map<String,Object>> source,int i) {
        if(i>=source.size()){row.add("");row.add("");row.add("");return;}
        var entry=source.get(i);row.add(entry.get("name"));row.add(entry.get("count"));row.add(entry.getOrDefault("boxes",""));
    }
    private static String csvText(Object value){String text=String.valueOf(value);if(!(value instanceof Number)&&text.matches("(?s)^[\\s]*[=+@-].*"))text="'"+text;return "\""+text.replace("\"","\"\"")+"\"";}
    private static void csv(Path file,List<List<Object>> rows)throws IOException {
        try(var output=Files.newBufferedWriter(file,StandardCharsets.UTF_8)) {output.write('\ufeff');for(var row:rows){for(int i=0;i<row.size();i++){if(i>0)output.write(',');output.write(csvText(row.get(i)));}output.write("\r\n");}}
    }
    private static String xml(Object value){return String.valueOf(value).replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]","").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static void entry(ZipOutputStream output,String name,String text)throws IOException{output.putNextEntry(new ZipEntry(name));output.write(text.getBytes(StandardCharsets.UTF_8));output.closeEntry();}
    private static String cell(int column,int row,Object value,int style) {
        String reference=""+(char)('A'+column)+row;
        if(value instanceof Number)return "<c r=\""+reference+"\" s=\""+style+"\"><v>"+value+"</v></c>";
        return "<c r=\""+reference+"\" s=\""+style+"\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"+xml(value)+"</t></is></c>";
    }
    private static void xlsx(Path file,List<List<Object>> rows,String source,List<String> warnings)throws IOException {
        int columns=rows.getFirst().size(),lastRow=rows.size()+4;String last=""+(char)('A'+columns-1);
        String note="需盒按每种物品堆叠上限与潜影盒 27 格换算，达到半盒才显示，整盒数向上取整。"+(warnings.isEmpty()?"":" "+String.join(" ",warnings));
        int noteUnits=note.codePoints().map(cp->cp>255?2:1).sum(),sourceUnits=source.codePoints().map(cp->cp>255?2:1).sum()+5;
        int noteHeight=Math.min(409,Math.max(45,((noteUnits+(columns==7?129:59))/(columns==7?130:60))*15+10));
        int sourceHeight=Math.min(409,Math.max(30,((sourceUnits+(columns==7?129:59))/(columns==7?130:60))*15+10));
        var sheet=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:"+last+lastRow+"\"/><sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\"><pane ySplit=\"5\" topLeftCell=\"A6\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols>");
        for(int i=0;i<columns;i++)sheet.append("<col min=\"").append(i+1).append("\" max=\"").append(i+1).append("\" width=\"").append(columns==7&&i==3?3:(i==0||columns==7&&i==4)?42:14).append("\" customWidth=\"1\"/>");
        sheet.append("</cols><sheetData><row r=\"1\" ht=\"30\" customHeight=\"1\">").append(cell(0,1,"投影材料清单",3)).append("</row><row r=\"2\" ht=\"").append(sourceHeight).append("\" customHeight=\"1\">").append(cell(0,2,"投影："+source,4)).append("</row><row r=\"3\" ht=\"").append(noteHeight).append("\" customHeight=\"1\">").append(cell(0,3,note,4)).append("</row>");
        for(int i=0;i<rows.size();i++) {int n=i+5;sheet.append("<row r=\"").append(n).append("\" ht=\"25\" customHeight=\"1\">");var row=rows.get(i);for(int c=0;c<columns;c++)sheet.append(cell(c,n,row.get(c),i==0&&!row.get(c).equals("")?1:row.get(c) instanceof Number?2:0));sheet.append("</row>");}
        sheet.append("</sheetData><autoFilter ref=\"A5:").append(last).append(lastRow).append("\"/><mergeCells count=\"3\"><mergeCell ref=\"A1:").append(last).append("1\"/><mergeCell ref=\"A2:").append(last).append("2\"/><mergeCell ref=\"A3:").append(last).append("3\"/></mergeCells><pageMargins left=\"0.3\" right=\"0.3\" top=\"0.4\" bottom=\"0.4\" header=\"0.2\" footer=\"0.2\"/><pageSetup orientation=\"landscape\" paperSize=\"9\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>");
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))) {
            entry(zip,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
            entry(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip,"xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"材料清单\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip,"xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            entry(zip,"xl/styles.xml","<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Microsoft YaHei\"/></font><font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"Microsoft YaHei\"/></font><font><b/><color rgb=\"FF203A55\"/><sz val=\"16\"/><name val=\"Microsoft YaHei\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF294F73\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"5\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"><alignment vertical=\"center\"/></xf><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFill=\"1\" applyFont=\"1\"><alignment vertical=\"center\"/></xf><xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf><xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"><alignment wrapText=\"1\" vertical=\"center\"/></xf></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            entry(zip,"xl/worksheets/sheet1.xml",sheet.toString());
        }
    }
}
