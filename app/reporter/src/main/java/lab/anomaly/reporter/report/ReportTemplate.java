package lab.anomaly.reporter.report;

/**
 * Template Method (design R5): the sections and their order are fixed here; each section is one method that a
 * report type fills in. The completion line is added last, over everything above it.
 */
public abstract class ReportTemplate {

    public final String render(ReportData data) {
        StringBuilder md = new StringBuilder(16_384);
        headline(md, data);
        incidentTable(md, data);
        incidentSections(md, data);
        brokeMostOften(md, data);
        movedMost(md, data);
        unclassified(md, data);
        return Completion.seal(md.toString(), data.incidents().size());
    }

    protected abstract void headline(StringBuilder md, ReportData data);

    protected abstract void incidentTable(StringBuilder md, ReportData data);

    protected abstract void incidentSections(StringBuilder md, ReportData data);

    protected abstract void brokeMostOften(StringBuilder md, ReportData data);

    protected abstract void movedMost(StringBuilder md, ReportData data);

    protected abstract void unclassified(StringBuilder md, ReportData data);
}
