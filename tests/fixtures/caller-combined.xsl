<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:hub="http://transpect.io/hub" xmlns:dbk="http://docbook.org/ns/docbook"
  exclude-result-prefixes="hub dbk">
  <xsl:import href="http://transpect.io/evolve-hub/xsl/evolve-hub.xsl"/>
  <xsl:template match="dbk:tab" mode="hub:tabs-to-indent" priority="50">
    <xsl:message>composition-test: next-match reached</xsl:message>
    <xsl:next-match>
      <xsl:with-param name="remove-nodes" select="if (../@xml:id = 'internal') then . else ()" tunnel="yes"/>
    </xsl:next-match>
  </xsl:template>
</xsl:stylesheet>
