(ns genegraph.transform.gene-validity.gci-model
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]
            [clojure.string :as s]
            [clojure.walk :refer [postwalk]]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [io.pedestal.interceptor :as interceptor])
  (:import [java.io ByteArrayInputStream]))

(def base "http://dataexchange.clinicalgenome.org/gci/")
(def legacy-report-base "http://dataexchange.clinicalgenome.org/gci/legacy-report_")
(def affbase "http://dataexchange.clinicalgenome.org/agent/")

(def ns-prefixes {"dx" "http://dataexchange.clinicalgenome.org/"
                  "sepio" "http://purl.obolibrary.org/obo/SEPIO_"
                  "hp" "http://purl.obolibrary.org/obo/HP_"
                  "dc" "http://purl.org/dc/terms/"
                  "rdfs" "http://www.w3.org/2000/01/rdf-schema#"
                  "dxgci" "http://dataexchange.clinicalgenome.org/gci/"
                  "ro" "http://purl.obolibrary.org/obo/RO_"
                  "mondo" "http://purl.obolibrary.org/obo/MONDO_"
                  "car" "http://reg.genome.network/allele/"
                  "cv" "https://www.ncbi.nlm.nih.gov/clinvar/variation/"
                  "hgnc" "https://identifiers.org/hgnc:"
                  "pmid" "https://pubmed.ncbi.nlm.nih.gov/"
                  "geno" "http://purl.obolibrary.org/obo/GENO_"})

(def context
  (s/join ""
        (drop-last
         (json/write-str
          {"@context" 
           {
            ;; frontmatter
            "@vocab" "http://dataexchange.clinicalgenome.org/gci/"
            "@base" "http://dataexchange.clinicalgenome.org/gci/"

            "PK" "@id"
            "item_type" "@type"
            "uuid" "@id"

            "gci" "http://dataexchange.clinicalgenome.org/gci/"
            "gcixform" "http://dataexchange.clinicalgenome.org/gcixform/"
            "cg" "http://dataexchange.clinicalgenome.org/terms/"

            ;; ;; common prefixes
            "HGNC" "https://identifiers.org/hgnc:"
            "MONDO" "http://purl.obolibrary.org/obo/MONDO_"
            "SEPIO" "http://purl.obolibrary.org/obo/SEPIO_"
            "GENO" "http://purl.obolibrary.org/obo/GENO_"
            "NCIT" "http://purl.obolibrary.org/obo/NCIT_"
            "HP" {"@id" "http://purl.obolibrary.org/obo/HP_"
                  "@prefix" true}
            ;; ;; declare attributes with @id, @vocab types
            "hgncId" {"@type" "@id"}

            "autoClassification" {"@type" "@vocab"}
            "alteredClassification" {"@type" "@vocab"}
            "hpoIdInDiagnosis" {"@type" "@id"}
            "diseaseId" {"@type" "@id"}
            "disease" {"@type" "@id"}
            "caseInfoType" {"@type" "@id"}
            "variantType" {"@type" "@id"}
            "caseControl" {"@type" "@id"}
            "affiliation" {"@type" "@id"}
            ;; "experimental_scored" {"@type" "@id"}
            ;; "caseControl_scored" {"@type" "@id"}
            ;; "variants" {"@type" "@id"}

            "modelSystemsType" {"@type" "@vocab"}
            "evidenceType" {"@type" "@vocab"}
            "functionalAlterationType" {"@type" "@vocab"}
            "rescueType" {"@type" "@vocab"}
            "studyType" {"@type" "@vocab"}
            "sequencingMethod" {"@type" "@vocab"}
            "authors" {"@container" "@list"}
            "recessiveZygosity" {"@type" "@vocab"}
            "sopVersion" {"@type" "@vocab"}
            "sex" {"@type" "@vocab"}
            "ethnicity" {"@type" "@vocab"}
            "ageType" {"@type" "@vocab"}
            "ageUnit" {"@type" "@vocab"}
            "scoreStatus" {"@type" "@vocab"}
            "interactionType" {"@type" "@vocab"}
            "probandIs" {"@type" "@vocab"}
            "genotypingMethods" {"@container" "@list"}
            "curationReasons" {"@type" "@vocab"}

            ;; ;; Category names
            "Model Systems" "gcixform:ModelSystems"
            "Functional Alteration" "gcixform:FunctionalAlteration"
            "Case control" "gcixform:CaseControl"

            ;; Case control
            "Aggregate variant analysis" "cg:AggregateVariantAnalysis"
            "Single variant analysis" "cg:SingleVariantAnalysis"

            ;; segregation

            ;; "Candidate gene sequencing" "http://purl.obolibrary.org/obo/SEPIO_0004543"
            ;; "Exome genome or all genes sequenced in linkage region" "http://purl.obolibrary.org/obo/SEPIO_0004541"
            "Candidate gene sequencing" "cg:CandidateGeneSequencing"
            "Exome genome or all genes sequenced in linkage region"
            "cg:AllGenesSequencing"

            ;; Experimental evidence types
            "Expression" "gcixform:Expression"
            "Biochemical Function" "gcixform:BiochemicalFunction"
            "Protein Interactions" "gcixform:ProteinInteraction"

            ;; rescue
            "Cell culture" "gcixform:CellCulture"
            "Non-human model organism" "gcixform:NonHumanModel"
            "Patient cells" "gcixform:PatientCells"
            "Human" "gcixform:Human"

            ;; model systems
            "Cell culture model" "gcixform:CellCultureModel"

            ;; functional alteration
            "Non-patient cells" "gcixform:NonPatientCells"
            "patient cells" "gcixform:PatientCells"

            ;; ;; evidence strength
            "No Modification" "gcixform:NoModification"
            ;; "Definitive" "http://purl.obolibrary.org/obo/SEPIO_0004504"
            ;; "Strong" "http://purl.obolibrary.org/obo/SEPIO_0004505"
            ;; "Moderate" "http://purl.obolibrary.org/obo/SEPIO_0004506"
            ;; "Limited" "http://purl.obolibrary.org/obo/SEPIO_0004507"
            ;; "No Known Disease Relationship" "http://purl.obolibrary.org/obo/SEPIO_0004508"
            ;; "No Reported Evidence" "http://purl.obolibrary.org/obo/SEPIO_0004508" ;; investigate the use of this
            ;; "Refuted" "http://purl.obolibrary.org/obo/SEPIO_0004510"
            ;; "Disputed" "http://purl.obolibrary.org/obo/SEPIO_0004540"
            ;; "No Classification" "http://purl.obolibrary.org/obo/SEPIO_0004508"

            "Definitive" "cg:Definitive"
            "Strong" "cg:Strong"
            "Moderate" "cg:Moderate"
            "Limited" "cg:Limited"
            "No Known Disease Relationship" "cg:NoKnownDiseaseRelationship"
            "No Reported Evidence" "cg:NoKnownDiseaseRelationship"
            "Refuted" "cg:Refuted"
            "Disputed" "cg:Disputed"
            "No Classification" "cg:NoKnownDiseaseRelationship"
            
            ;; Zygosity
            ;; "Homozygous" "http://purl.obolibrary.org/obo/GENO_0000136"
            ;; "TwoTrans" "http://purl.obolibrary.org/obo/GENO_0000135"
            ;; "Hemizygous" "http://purl.obolibrary.org/obo/GENO_0000134"

            "Homozygous" "cg:Homozygous"
            "TwoTrans" "cg:TwoVariantsInTrans"
            "Hemizygous" "cg:Hemizygous"

            ;; SOP versions
            ;; "4" "http://purl.obolibrary.org/obo/SEPIO_0004092"
            ;; "5" "http://purl.obolibrary.org/obo/SEPIO_0004093"
            ;; "6" "http://purl.obolibrary.org/obo/SEPIO_0004094"
            ;; "7" "http://purl.obolibrary.org/obo/SEPIO_0004095"
            ;; "8" "http://purl.obolibrary.org/obo/SEPIO_0004096"
            ;; "9" "http://purl.obolibrary.org/obo/SEPIO_0004171"
            ;; "10" "http://purl.obolibrary.org/obo/SEPIO_0004190"

            "4" "cg:GeneValidityCriteria4"
            "5" "cg:GeneValidityCriteria5"
            "6" "cg:GeneValidityCriteria6"
            "7" "cg:GeneValidityCriteria7"
            "8" "cg:GeneValidityCriteria8"
            "9" "cg:GeneValidityCriteria9"
            "10" "cg:GeneValidityCriteria10"
            "11" "cg:GeneValidityCriteria11"


            ;; Sex
            ;; "Ambiguous" "http://purl.obolibrary.org/obo/SEPIO_0004574"
            ;; "Female" "http://purl.obolibrary.org/obo/SEPIO_0004575"
            ;; "Intersex" "http://purl.obolibrary.org/obo/SEPIO_0004576"
            ;; "Male" "http://purl.obolibrary.org/obo/SEPIO_0004578"

            "Ambiguous" "cg:AmbiguousSex"
            "Female" "cg:Female"
            "Intersex" "cg:Intersex"
            "Male" "cg:Male"

            ;; ethnicity
            ;; "Hispanic or Latino" "http://purl.obolibrary.org/obo/SEPIO_0004568"
            ;; "Not Hispanic or Latino" "http://purl.obolibrary.org/obo/SEPIO_0004569"
            ;; "Unknown" "http://purl.obolibrary.org/obo/SEPIO_0004570"

            "Hispanic or Latino" "cg:HispanicOrLatino"
            "Not Hispanic or Latino" "cg:NotHispanicOrLatino"
            "Unknown" "cg:UnknownEthnicity"

            ;; Consider restructring this part of the model
            ;; ageType
            "Death" "cg:AgeAtDeath"
            "Diagnosis" "cg:AgeAtDiagnosis"
            "Onset" "cg:AgeAtOnset"
            "Report" "cg:AgeAtReport"
 
            ;; ageUnit
            ;; "Days" "http://purl.obolibrary.org/obo/SEPIO_0004552"
            ;; "Hours" "http://purl.obolibrary.org/obo/SEPIO_0004553"
            ;; "Months" "http://purl.obolibrary.org/obo/SEPIO_0004554"
            ;; "Weeks" "http://purl.obolibrary.org/obo/SEPIO_0004555"
            ;; "Weeks gestation" "http://purl.obolibrary.org/obo/SEPIO_0004556" 
            ;; "Years" "http://purl.obolibrary.org/obo/SEPIO_0004557"

            "Days" "cg:Days"
            "Hours" "cg:Hours"
            "Months" "cg:Months"
            "Weeks" "cg:Weeks"
            "Weeks gestation" "cg:WeeksGestation"
            "Years" "cg:Years"

            ;; Consider revisiting this part of the model
            ;; scoreStatus
            "Contradicts" "cg:Contradicts"
            "Review" "cg:Inconclusive"
            "Score" "cg:Supports"
            "Supports" "cg:Supports"
            "none" "cg:Inconclusive"

            ;; testingMethods
            ;; "Chromosomal microarray" "http://purl.obolibrary.org/obo/SEPIO_0004591"
            ;; "Denaturing gradient gel" "http://purl.obolibrary.org/obo/SEPIO_0004592"
            ;; "Exome sequencing" "http://purl.obolibrary.org/obo/SEPIO_0004593"
            ;; "Genotyping" "http://purl.obolibrary.org/obo/SEPIO_0004594"
            ;; "High resolution melting" "http://purl.obolibrary.org/obo/SEPIO_0004595"
            ;; "Homozygosity mapping" "http://purl.obolibrary.org/obo/SEPIO_0004596"
            ;; "Linkage analysis" "http://purl.obolibrary.org/obo/SEPIO_0004597"
            ;; "Next generation sequencing panels" "http://purl.obolibrary.org/obo/SEPIO_0004598"
            ;; "Other" "http://purl.obolibrary.org/obo/SEPIO_0004599"
            ;; "PCR" "http://purl.obolibrary.org/obo/SEPIO_0004600"
            ;; "Restriction digest" "http://purl.obolibrary.org/obo/SEPIO_0004601"
            ;; "SSCP" "http://purl.obolibrary.org/obo/SEPIO_0004602"
            ;; "Sanger sequencing" "http://purl.obolibrary.org/obo/SEPIO_0004603"
            ;; "Whole genome shotgun sequencing" "http://purl.obolibrary.org/obo/SEPIO_0004604"
            
            "Chromosomal microarray" "cg:ChromosomalMicroarray"
            "Denaturing gradient gel" "cg:DenaturingGradientGel"
            "Exome sequencing" "cg:ExomeSequencing"
            "Genotyping" "cg:Genotyping"
            "High resolution melting" "cg:HighResolutionMelting"
            "Homozygosity mapping" "cg:HomozygosityMapping"
            "Linkage analysis" "cg:LinkageAnalysis"
            "Next generation sequencing panels" "cg:GenePanels"
            "Other" "cg:OtherVariantDetectionMethod"
            "PCR" "cg:PCR"
            "Restriction digest" "cg:RestrictionDigest"
            "SSCP" "cg:SSCP"
            "Sanger sequencing" "cg:SangerSequencing"
            "Whole genome shotgun sequencing" "cg:WholeGenomeSequencing"

            ;; variantType
            "OTHER_VARIANT_TYPE" "cg:OtherVariant"
            "PREDICTED_OR_PROVEN_NULL" "cg:NullVariant"

            ;; interactionTypes
            "genetic interaction" "gcixform:GeneticInteraction"
            "negative genetic interaction" "gcixform:NegativeGeneticInteraction"
            "physical association" "gcixform:PhysicalAssociation"
            "positive genetic interaction" "gcixform:PositiveGeneticInteraction"

            ;; probandIs
            ;; "Biallelic compound heterozygous" "http://purl.obolibrary.org/obo/GENO_0000402"
            ;; "Biallelic homozygous" "http://purl.obolibrary.org/obo/GENO_0000136"
            ;; "Monoallelic heterozygous"  "http://purl.obolibrary.org/obo/GENO_0000135"

            "Biallelic compound heterozygous" "cg:BiallelicCompoundHeterozygous"
            "Biallelic homozygous" "cg:BiallelicHomozygous"
            "Monoallelic heterozygous" "cg:MonoallelicHeterozygous"

            ;; update reasons
            "New Curation" "cg:NewCuration"
            "Adminstrative Update Disease Name Update" "cg:DiseaseNameUpdate"
            "Administrative Update Error Clarification" "cg:ErrorClarification"
            "Recuration Community Request" "cg:RecurationCommunityRequest"
            "Recuration Due to Timing" "cg:RecurationTiming"
            "Recuration New Evidence" "cg:RecurationNewEvidence"
            "Recuration Framework Change" "cg:RecurationFrameworkChange"
            "Recuration Error affecting score and or classification" "cg:RecurationErrorAffectingScoreorClassification"
            
            }}))))

(defn expand-affiliation-to-iri
  "Expand affiliation when a simple string field, to be an iri"
  [m]
  (if (and (map? m) (get m :affiliation))
    (update m :affiliation (fn [affiliation]
                              (if (coll? affiliation)
                                affiliation
                                (str affbase affiliation))))
    m))

(defn fix-hpo-ids [m]
  (if (and (map? m) (get m :hpoIdInDiagnosis))
    (update m :hpoIdInDiagnosis (fn [phenotypes]
                                   (mapv #(re-find #"HP:\d{7}" %)
                                         phenotypes)))
    m))

(defn clear-associated-snapshots [m]
  (if (map? m) (dissoc m :associatedClassificationSnapshots) m))

(defn remove-keys-when-empty
  "When element is a map, removes any keys with key names from 'keys' vector that
  has an empty value." 
  [element keys]
  (postwalk (fn [x] (if (map? x)
                      (->> (select-keys x keys)
                           (reduce (fn [coll [k v]]
                                     (if (empty? v) (conj coll k) coll))
                                   [])
                           (apply dissoc x))
                      x))
            element))

(defn preprocess-json
  "Walk GCI JSON prior to parsing as JSON-LD to clean up data."
  [data]
  (json/write-str
   (postwalk #(-> %
                  clear-associated-snapshots
                  fix-hpo-ids
                  expand-affiliation-to-iri
                  (remove-keys-when-empty [:geneWithSameFunctionSameDisease
                                           :normalExpression
                                           :scores
                                           :carId
                                           :clinvarVariantId]))
             data)))

(defn fix-gdm-identifiers [gdm-json]
  (-> gdm-json
      (s/replace #"MONDO_" "http://purl.obolibrary.org/obo/MONDO_")
      ;; New json-ld parser doesn't like '/' or parenthesis in terms 
      (s/replace #"Exome\\/genome or all genes sequenced in linkage region"
                 "Exome genome or all genes sequenced in linkage region")
      ;; these are the interactionType MI codes only -  MI codes are used
      ;; in at least one other field in the json. Removing the MI code
      ;; completely as we are not preserving the actual interactionType
      (s/replace #" \(MI:0208\)| \(MI:0915\)| \(MI:0933\)| \(MI:0935\)" "")
      (s/replace #"@id" "gciid")))

(defn append-context [gdm-json]
  (str context "," (subs gdm-json 1)))

(defn add-gci-model-fn [event]
  (assoc event
         :gene-validity/gci-model
         (-> (::event/data event)
             preprocess-json
             fix-gdm-identifiers
             append-context
             .getBytes
             ByteArrayInputStream.
             (rdf/read-rdf :json-ld))))

(def add-gci-model
  (interceptor/interceptor
   {:name ::add-gci-model
    :enter (fn [e] (add-gci-model-fn e))}))


