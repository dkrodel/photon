/*
 *
 * Copyright 2015 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package com.netflix.imflibrary.writerTools;

import com.netflix.imflibrary.IMFErrorLogger;
import com.netflix.imflibrary.exceptions.IMFAuthoringException;
import com.netflix.imflibrary.utils.ErrorLogger;
import com.netflix.imflibrary.utils.UUIDHelper;
import com.netflix.imflibrary.writerTools.utils.ValidationEventHandlerImpl;
import org.smpte_ra.schemas.st0429_9_2007.AM.AssetType;
import org.xml.sax.SAXException;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.ValidationEventHandler;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A class that implements the logic to build a SMPTE st0429-9:2007 schema compliant AssetMap document.
 */
public class AssetMapBuilder {

    private final UUID uuid;
    private final org.smpte_ra.schemas.st0429_9_2007.AM.UserText annotationText;
    private final org.smpte_ra.schemas.st0429_9_2007.AM.UserText creator;
    private final XMLGregorianCalendar issueDate;
    private final org.smpte_ra.schemas.st0429_9_2007.AM.UserText issuer;
    private final List<AssetMapBuilder.Asset> assets;
    private final File workingDirectory;
    private final IMFErrorLogger imfErrorLogger;

    /**
     * A constructor for the AssetMapBuilder object
     * @param uuid
     * @param annotationText
     * @param creator
     * @param issueDate
     * @param issuer
     * @param assets
     * @param workingDirectory
     * @param imfErrorLogger
     */
    public AssetMapBuilder(@Nonnull UUID uuid,
                           @Nonnull org.smpte_ra.schemas.st0429_9_2007.AM.UserText annotationText,
                           @Nonnull org.smpte_ra.schemas.st0429_9_2007.AM.UserText creator,
                           @Nonnull XMLGregorianCalendar issueDate,
                           @Nonnull org.smpte_ra.schemas.st0429_9_2007.AM.UserText issuer,
                           @Nonnull List<AssetMapBuilder.Asset> assets,
                           @Nonnull File workingDirectory,
                           @Nonnull IMFErrorLogger imfErrorLogger){
        this.uuid = uuid;
        this.annotationText = annotationText;
        this.creator = creator;
        this.issueDate = issueDate;
        this.issuer = issuer;
        this.assets = Collections.unmodifiableList(assets);
        this.workingDirectory = workingDirectory;
        this.imfErrorLogger = imfErrorLogger;
    }

    public File build() throws IOException, SAXException, JAXBException {

        int numErrors = imfErrorLogger.getNumberOfErrors();

        org.smpte_ra.schemas.st0429_9_2007.AM.AssetMapType assetMapType = IMFAssetMapObjectFieldsFactory.constructAssetMapType_2007();
        assetMapType.setId(UUIDHelper.fromUUID(this.uuid));
        assetMapType.setAnnotationText(this.annotationText);
        assetMapType.setCreator(this.creator);
        assetMapType.setVolumeCount(new BigInteger("1"));//According to st0429-9:2014 this should be set to 1.
        assetMapType.setIssueDate(this.issueDate);
        assetMapType.setIssuer(this.issuer);
        /**
         * Constructing the AssetList attribute of the AssetMap
         */
        org.smpte_ra.schemas.st0429_9_2007.AM.AssetMapType.AssetList assetList = new org.smpte_ra.schemas.st0429_9_2007.AM.AssetMapType.AssetList();
        List<org.smpte_ra.schemas.st0429_9_2007.AM.AssetType> assetMapTypeAssets = assetList.getAsset();
        for(AssetMapBuilder.Asset assetMapBuilderAsset : this.assets){
            org.smpte_ra.schemas.st0429_9_2007.AM.AssetType assetType = new AssetType();
            assetType.setId(assetMapBuilderAsset.uuid);
            assetType.setPackingList(assetMapBuilderAsset.packingList);
            org.smpte_ra.schemas.st0429_9_2007.AM.AssetType.ChunkList chunkList = new org.smpte_ra.schemas.st0429_9_2007.AM.AssetType.ChunkList();
            assetType.setChunkList(chunkList);
            List<org.smpte_ra.schemas.st0429_9_2007.AM.ChunkType> chunkTypes = chunkList.getChunk();
            List<AssetMapBuilder.Chunk> chunks = assetMapBuilderAsset.getChunks();
            for(Chunk chunk : chunks){
                org.smpte_ra.schemas.st0429_9_2007.AM.ChunkType assetMapAssetChunk = new org.smpte_ra.schemas.st0429_9_2007.AM.ChunkType();
                assetMapAssetChunk.setPath(chunk.getPath());
                assetMapAssetChunk.setVolumeIndex(chunk.getVolumeIndex());
                assetMapAssetChunk.setOffset(chunk.getOffset());
                assetMapAssetChunk.setLength(chunk.getLength());
                chunkTypes.add(assetMapAssetChunk);
            }
            assetMapTypeAssets.add(assetType);
        }
        assetMapType.setAssetList(assetList);

        File outputFile = new File(this.workingDirectory + "/" + "AssetMap" + ".xml");
        List<ErrorLogger.ErrorObject> errors = serializeAssetMapToXML(assetMapType, outputFile, true);

        if(errors.size() > numErrors){
            List<ErrorLogger.ErrorObject> fatalErrors = imfErrorLogger.getErrors(IMFErrorLogger.IMFErrors.ErrorLevels.FATAL, numErrors, errors.size());
            if(fatalErrors.size() > 0){
                throw new IMFAuthoringException(String.format("Following FATAL errors were detected while building and AssetMap document %s", fatalErrors.toString()));
            }
        }
        return outputFile;
    }

    /**
     * A thin Immutable class roughly modeling the Asset Type in an AssetMap
     */
    @Immutable
    public static final class Asset{
        private final String uuid;
        private final org.smpte_ra.schemas.st0429_9_2007.AM.UserText annotationText;
        private final boolean packingList;
        private final List<Chunk> chunks;

        /**
         * A constructor of the AssetMapBuilder's Asset class.
         * @param uuid a raw UUID that identifies this Asset in the AssetMap
         * @param annotationText org.smpte_ra.schemas.st0429_9_2007.AM.UserText type
         * @param packingList a boolean indicating if this Asset is a packing list or not
         * @param chunks a list of AssetMapBuilder.Chunk type roughly modeling an AssetMap Asset's chunk
         */
        public Asset(UUID uuid,
                     org.smpte_ra.schemas.st0429_9_2007.AM.UserText annotationText,
                     boolean packingList,
                     List<Chunk> chunks){
            this.uuid = UUIDHelper.fromUUID(uuid);
            this.annotationText = annotationText;
            this.packingList = packingList;
            this.chunks = Collections.unmodifiableList(chunks);
        }

        /**
         * Getter for the urn:uuid: that uniquely identifies this asset
         * @return a "urn:uuid:" string
         */
        public String getUuid(){
            return this.uuid;
        }

        /**
         * Getter for the Asset's annotation text
         * @return org.smpte_ra.schemas.st0429_9_2007.AM.UserText of the Asset
         */
        public org.smpte_ra.schemas.st0429_9_2007.AM.UserText getAnnotationText(){
            return this.annotationText;
        }

        /**
         * Getter for the boolean indicating PackingList
         * @return boolean to indicate if this asset is a PackingList or not
         */
        public boolean isPackingList(){
            return this.packingList;
        }

        /**
         * Getter for the list of Chunks representing the Asset
         * @return a list of chunks corresponding to the Asset
         */
        public List<Chunk> getChunks(){
            return this.chunks;
        }
    }

    /**
     * A thin immutable class roughly modeling the concept of a Chunk of an AssetMap's asset.
     */
    @Immutable
    public static final class Chunk {
        private final String path;
        private final BigInteger volumeIndex = BigInteger.valueOf(1); //According to st0429-9:2014 this should be 1 or absent.
        private final BigInteger offset = BigInteger.valueOf(0); //According to st0429-9:2014 this should be 0 or absent.
        private final BigInteger length;

        /**
         * A constructor of the AssetMapBuilder's Chunk class.
         * @param path a URI compliant with the following RegEx : "^[a-zA-Z0-9._-]+"
         * @param length in bytes of the chunk of the asset
         */
        public Chunk(String path, Long length) throws URISyntaxException {
            if(path.matches("^[a-zA-Z0-9._-]+") == true) {
                this.path = path;
            }
            else{
                throw new URISyntaxException(path, String.format("The Asset path %s does not conform to the specified URI syntax in Annex-A of st429-9:2014 (a-z, A-Z, 0-9, ., _, -)", path));
            }
            this.length = BigInteger.valueOf(length);
        }

        /**
         * Getter for the path of a chunk
         * @return a string representation of the path to a chunk of an Asset
         */
        public String getPath(){
            return this.path;
        }

        /**
         * Getter for the VolumeIndex of a chunk
         * @return a BigInteger corresponding to the VolumeIndex of the chunk
         */
        @Nonnull
        public BigInteger getVolumeIndex(){
            return this.volumeIndex;
        }

        /**
         * Getter for the offset of a chunk
         * @return a BigInteger representing the offset of the chunk
         */
        @Nonnull
        public BigInteger getOffset(){
            return this.offset;
        }

        /**
         * Getter for the length of a chunk
         * @return a BigInteger representing the length of the chunk in bytes
         */
        @Nonnull
        public BigInteger getLength(){
            return this.length;
        }
    }

    private List<IMFErrorLogger.ErrorObject> serializeAssetMapToXML(org.smpte_ra.schemas.st0429_9_2007.AM.AssetMapType assetMapType, File outputFile, boolean formatted) throws IOException, SAXException, JAXBException {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try(
            InputStream assetMapSchemaAsAStream = contextClassLoader.getResourceAsStream("org/smpte_ra/schemas/st0429_9_2007/AM/assetMap_schema.xsd");
            OutputStream outputStream = new FileOutputStream(outputFile);
        )
        {
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI );
            StreamSource[] schemaSources = new StreamSource[1];
            schemaSources[0] = new StreamSource(assetMapSchemaAsAStream);
            Schema schema = schemaFactory.newSchema(schemaSources);

            JAXBContext jaxbContext = JAXBContext.newInstance("org.smpte_ra.schemas.st0429_9_2007.AM");
            Marshaller marshaller = jaxbContext.createMarshaller();
            ValidationEventHandlerImpl validationEventHandler = new ValidationEventHandlerImpl(true);
            marshaller.setEventHandler(validationEventHandler);
            marshaller.setSchema(schema);
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, formatted);

            /*marshaller.marshal(cplType, output);
            workaround for 'Error: unable to marshal type "AssetMapType" as an element because it is missing an @XmlRootElement annotation'
            as found at https://weblogs.java.net/blog/2006/03/03/why-does-jaxb-put-xmlrootelement-sometimes-not-always
             */
            marshaller.marshal(new JAXBElement<>(new QName("http://www.smpte-ra.org/schemas/429-9/2007/AM", "AssetMap"), org.smpte_ra.schemas.st0429_9_2007.AM.AssetMapType.class, assetMapType), outputStream);
            outputStream.close();

            if(validationEventHandler.hasErrors())
            {
                //TODO : Perhaps a candidate for a Lambda
                for(ValidationEventHandlerImpl.ValidationErrorObject validationErrorObject : validationEventHandler.getErrors()) {
                    this.imfErrorLogger.addError(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_AM_ERROR, validationErrorObject.getValidationEventSeverity(), validationErrorObject.getErrorMessage());
                }
            }
        }
        return imfErrorLogger.getErrors();
    }
}
