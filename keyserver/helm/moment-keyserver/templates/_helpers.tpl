{{- define "moment.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "moment.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 50 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "moment.name" .) | trunc 50 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- define "moment.selector" -}}
app.kubernetes.io/name: {{ include "moment.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: keyserver
{{- end -}}
{{- define "moment.labels" -}}
app.kubernetes.io/name: {{ include "moment.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
{{- end -}}
{{- define "moment.cluster" -}}
{{- default (printf "%s-pg" (include "moment.fullname" .)) .Values.postgresql.clusterName -}}
{{- end -}}
{{- define "moment.dbSecret" -}}
{{- if .Values.postgresql.enabled -}}
{{- default (printf "%s-app" (include "moment.cluster" .)) .Values.postgresql.existingSecret -}}
{{- else -}}
{{- required "externalPostgresql.existingSecret is required" .Values.externalPostgresql.existingSecret -}}
{{- end -}}
{{- end -}}
{{- define "moment.caSecret" -}}
{{- if .Values.postgresql.enabled -}}
{{- printf "%s-ca" (include "moment.cluster" .) -}}
{{- else -}}
{{- required "externalPostgresql.caSecret is required (TLS verify-full)" .Values.externalPostgresql.caSecret -}}
{{- end -}}
{{- end -}}
{{- define "moment.serviceAccount" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "moment.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
